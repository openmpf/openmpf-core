/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2019 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2019 The MITRE Corporation                                       *
 *                                                                            *
 * Licensed under the Apache License, Version 2.0 (the "License");            *
 * you may not use this file except in compliance with the License.           *
 * You may obtain a copy of the License at                                    *
 *                                                                            *
 *    http://www.apache.org/licenses/LICENSE-2.0                              *
 *                                                                            *
 * Unless required by applicable law or agreed to in writing, software        *
 * distributed under the License is distributed on an "AS IS" BASIS,          *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.   *
 * See the License for the specific language governing permissions and        *
 * limitations under the License.                                             *
 ******************************************************************************/

package org.mitre.mpf.wfm.camel.operations.detection.artifactextraction;

import org.apache.camel.Exchange;
import org.mitre.mpf.interop.JsonDetectionOutputObject;
import org.mitre.mpf.wfm.camel.WfmProcessor;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.transients.*;
import org.mitre.mpf.wfm.enums.ErrorCodes;
import org.mitre.mpf.wfm.enums.*;
import org.mitre.mpf.wfm.service.StorageService;
import org.mitre.mpf.wfm.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.google.common.collect.Table;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.util.SortedSet;
import java.util.TreeSet;

import static java.util.stream.Collectors.toCollection;

/**
 * Extracts artifacts from a media file based on the contents of the
 * {@link ArtifactExtractionRequest} contained in the incoming message body.
 */
@Component(ArtifactExtractionProcessor.REF)
public class ArtifactExtractionProcessor extends WfmProcessor {

    public static final String REF = "trackDetectionExtractionProcessor";

    private static final Logger LOG = LoggerFactory.getLogger(ArtifactExtractionProcessor.class);

    private final JsonUtils _jsonUtils;

    private final InProgressBatchJobsService _inProgressBatchJobs;

    private final StorageService _storageService;

    @Inject
    ArtifactExtractionProcessor(
            JsonUtils jsonUtils,
            InProgressBatchJobsService inProgressBatchJobs,
            StorageService storageService) {
        _jsonUtils = jsonUtils;
        _inProgressBatchJobs = inProgressBatchJobs;
        _storageService = storageService;
    }

    @Override
    public void wfmProcess(Exchange exchange) {
        ArtifactExtractionRequest request = _jsonUtils.deserialize(exchange.getIn().getBody(byte[].class),
                ArtifactExtractionRequest.class);
        switch (request.getMediaType()) {
            case IMAGE:
            case VIDEO:
                processExtractionRequest(request);
                break;
            default:
                _inProgressBatchJobs.setJobStatus(request.getJobId(), BatchJobStatusType.IN_PROGRESS_ERRORS);
                _inProgressBatchJobs.addError(
                        request.getJobId(), request.getMediaId(), ErrorCodes.ARTIFACT_EXTRACTION_ERROR,
                        "Error extracting artifacts(s) from frame(s): Unsupported media type"
                                    + request.getMediaType().name());
        }

        exchange.getOut().setHeader(MpfHeaders.CORRELATION_ID, exchange.getIn().getHeader(MpfHeaders.CORRELATION_ID));
        exchange.getOut().setHeader(MpfHeaders.SPLIT_SIZE, exchange.getIn().getHeader(MpfHeaders.SPLIT_SIZE));
    }


    private void setStatus(Detection detection, URI uri) {
        if (uri == null) {
            detection.setArtifactExtractionStatus(ArtifactExtractionStatus.FAILED);
        }
        else {
            detection.setArtifactExtractionStatus(ArtifactExtractionStatus.COMPLETED);
            detection.setArtifactPath(uri.toString());
        }
    }


    private void processExtractionRequest(ArtifactExtractionRequest request) {
        Table<Integer, Integer, URI> trackAndFrameToUri;
        try {
            trackAndFrameToUri = _storageService.storeArtifacts(request);
        } catch (IOException e) {
            handleException(request, e);
            return;
        }
        SortedSet<Track> jobTracks = _inProgressBatchJobs.getTracks(request.getJobId(), request.getMediaId(),
                request.getTaskIndex(), request.getActionIndex());
        // Set the status for the requested detections. If any were requested, but were not included in the extraction output,
        // they will be reported as missing frames.
        trackAndFrameToUri.cellSet().stream()
                .forEach(e -> request.getExtractionsMap().get(e.getColumnKey()).get(e.getRowKey()).setArtifactExtractionStatus("COMPLETED"));

        if (request.getCroppingFlag()) {
            for (Table.Cell<Integer, Integer, URI> entry : trackAndFrameToUri.cellSet()) {
                URI uri = entry.getValue();
                jobTracks.stream().filter(t -> t.getArtifactExtractionTrackIndex() == entry.getRowKey())
                        .flatMap(t -> t.getDetections().stream())
                        .filter(d -> d.getMediaOffsetFrame() == entry.getColumnKey())
                        .forEach(d -> setStatus(d, uri));
            }
        }
        else {
            for (Integer frame : trackAndFrameToUri.columnKeySet()) {
                // When we are not cropping, the track number is a don't care; it is set to 0 in the frame extraction code.
                URI uri = trackAndFrameToUri.get(0, frame);
                jobTracks.stream().flatMap(t -> t.getDetections().stream())
                        .filter(d -> d.getMediaOffsetFrame() == frame)
                        .forEach(d -> setStatus(d, uri));
            }
        }

        _inProgressBatchJobs.setTracks(request.getJobId(), request.getMediaId(),
                                       request.getTaskIndex(), request.getActionIndex(), jobTracks);

        SortedSet<Integer> missingFrames = findMissingFrames(jobTracks, request);
        if (!missingFrames.isEmpty()) {
            _inProgressBatchJobs.setJobStatus(request.getJobId(), BatchJobStatusType.IN_PROGRESS_ERRORS);
            _inProgressBatchJobs.addError(
                    request.getJobId(), request.getMediaId(), ErrorCodes.ARTIFACT_EXTRACTION_ERROR,
                    "Error extracting artifact(s) from frame(s): " + missingFrames);
        }
    }

    private SortedSet<Integer> findMissingFrames(SortedSet<Track> jobTracks, ArtifactExtractionRequest request) {

        // Check for frames in the tracks that failed.
        SortedSet<Integer> missingFrames = jobTracks.stream()
                                   .flatMap(t -> t.getDetections().stream())
                                   .filter(d ->(d.getArtifactExtractionStatus() == ArtifactExtractionStatus.FAILED))
                                   .map(Detection::getMediaOffsetFrame).collect(toCollection(TreeSet::new));
        // Check for frames that were supposed to be extracted but weren't
        SortedSet<Integer> missingRequestFrames = request.getExtractionsMap().values().stream()
                .flatMap(v -> v.values().stream())
                .filter(d -> d.getArtifactExtractionStatus().equals(ArtifactExtractionStatus.REQUESTED.name()))
                .map(JsonDetectionOutputObject::getOffsetFrame).collect(toCollection(TreeSet::new));
        missingFrames.addAll(missingRequestFrames);

        return missingFrames;
    }

    private void handleException(ArtifactExtractionRequest request, IOException e) {
        LOG.warn(
                "[Job {}|{}|ARTIFACT_EXTRACTION] Failed to extract the artifacts from Media #{} due to an "
                        + "exception. All detections (including exemplars) produced in this task "
                        + "for this medium will NOT have an associated artifact.",
                request.getJobId(), request.getTaskIndex(), request.getMediaId(), e);
        _inProgressBatchJobs.setJobStatus(request.getJobId(), BatchJobStatusType.IN_PROGRESS_ERRORS);
        SortedSet<Integer> missingFrames = findMissingFrames(_inProgressBatchJobs.getTracks(request.getJobId(), request.getMediaId(),
                request.getTaskIndex(), request.getActionIndex()), request);
        if (!missingFrames.isEmpty()) {
            _inProgressBatchJobs.addError(
                        request.getJobId(), request.getMediaId(), ErrorCodes.ARTIFACT_EXTRACTION_ERROR,
                        "Error extracting artifact(s) from frame(s): " + missingFrames);
        }
    }
}
