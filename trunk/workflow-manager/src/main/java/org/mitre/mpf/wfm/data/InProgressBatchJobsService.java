/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2021 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2021 The MITRE Corporation                                       *
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


package org.mitre.mpf.wfm.data;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import org.mitre.mpf.interop.JsonIssueDetails;
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.data.entities.persistent.*;
import org.mitre.mpf.wfm.data.entities.transients.Track;
import org.mitre.mpf.wfm.enums.*;
import org.mitre.mpf.wfm.util.FrameTimeInfo;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static java.util.stream.Collectors.joining;

@Component
@Singleton
public class InProgressBatchJobsService {

    private static final Logger LOG = LoggerFactory.getLogger(InProgressBatchJobsService.class);

    private final PropertiesUtil _propertiesUtil;

    private final Redis _redis;

    private final Map<Long, BatchJobImpl> _jobs = new HashMap<>();


    @Inject
    public InProgressBatchJobsService(PropertiesUtil propertiesUtil, Redis redis) {
        _propertiesUtil = propertiesUtil;
        _redis = redis;
    }


    public synchronized BatchJob addJob(
            long jobId,
            String externalId,
            SystemPropertiesSnapshot propertiesSnapshot,
            JobPipelineElements pipeline,
            int priority,
            boolean outputEnabled,
            String callbackUrl,
            String callbackMethod,
            Collection<Media> media,
            Map<String, String> jobProperties,
            Map<String, ? extends Map<String, String>> algorithmProperties) {

        if (_jobs.containsKey(jobId)) {
            throw new IllegalArgumentException(String.format("Job with id %s already exists.", jobId));
        }

        var mediaInfo = media.stream()
                .map(m -> String.format("\"%s\" (id=%s)", m.getUri(), m.getId()))
                .collect(joining(", "));

        LOG.info("Initializing batch job {} which will run the \"{}\" pipeline on the following " +
                         "media: {}", jobId, pipeline.getName(), mediaInfo);
        List<MediaImpl> mediaImpls = media.stream()
                .map(MediaImpl::toMediaImpl)
                .collect(ImmutableList.toImmutableList());

        var job = new BatchJobImpl(
                jobId,
                externalId,
                propertiesSnapshot,
                pipeline,
                priority,
                outputEnabled,
                callbackUrl,
                callbackMethod,
                mediaImpls,
                jobProperties,
                algorithmProperties);
        _jobs.put(jobId, job);

        media.stream()
                .filter(Media::isFailed)
                .forEach(m -> addError(jobId, m.getId(), IssueCodes.MEDIA_INITIALIZATION, m.getErrorMessage()));

        return job;
    }


    public synchronized BatchJob getJob(long jobId) {
        return getJobImpl(jobId);
    }


    private BatchJobImpl getJobImpl(long jobId) {
        BatchJobImpl job = _jobs.get(jobId);
        if (job != null) {
            return job;
        }
        throw new WfmProcessingException("Unable to locate batch job with id: " + jobId);
    }


    public synchronized void clearJob(long jobId) {
        LOG.info("Clearing all job information for job: {}", jobId);
        BatchJobImpl job = getJobImpl(jobId);
        _redis.clearTracks(job);
        _jobs.remove(jobId);
        for (Media media : job.getMedia()) {
            if (media.getUriScheme().isRemote()) {
                try {
                    Files.deleteIfExists(media.getLocalPath());
                }
                catch (IOException e) {
                    LOG.warn(String.format(
                            "Failed to delete the local file '%s' which was retrieved " +
                                    "from a remote location - it must be manually deleted.",
                            media.getLocalPath()), e);
                }
            }

            if (media.getConvertedMediaPath().isPresent()) {
                try {
                    Files.deleteIfExists(media.getConvertedMediaPath().get());
                }
                catch (IOException e) {
                    LOG.warn(String.format(
                            "Failed to delete the converted media file '%s' - " +
                                    "it must be manually deleted.",
                            media.getConvertedMediaPath().get()), e);
                }
            }
        }
    }

    public synchronized void clearOnInitializationError(long jobId) {
        _jobs.remove(jobId);
    }

    public synchronized boolean containsJob(long jobId) {
        return _jobs.containsKey(jobId);
    }

    public synchronized boolean cancelJob(long jobId) {
        LOG.info("Marking job {} as cancelled.", jobId);
        getJobImpl(jobId).setCancelled(true);
        return true;
    }

    public synchronized SortedSet<Track> getTracks(long jobId, long mediaId, int taskIndex, int actionIndex) {
        return _redis.getTracks(jobId, mediaId, taskIndex, actionIndex);
    }


    public synchronized void addTrack(Track track) {
        LOG.debug("Storing new track for job {}'s media {}.", track.getJobId(), track.getMediaId());
        _redis.addTrack(track);
    }

    public synchronized void setTracks(long jobId, long mediaId, int taskIndex, int actionIndex,
                                       Collection<Track> tracks) {
        LOG.info("Replacing tracks for job {}'s media {}", jobId, mediaId);
        _redis.setTracks(jobId, mediaId, taskIndex, actionIndex, tracks);
    }


    public synchronized void addJobWarning(long jobId, IssueCodes code, String message) {
        addWarning(jobId, 0, code, message);
    }

    public synchronized void addWarning(long jobId, long mediaId, IssueCodes code, String message) {
        addWarning(jobId, mediaId, code, message, IssueSources.WORKFLOW_MANAGER);
    }

    public synchronized void addWarning(long jobId, long mediaId, IssueCodes code, String message,
                                        IssueSources source) {
        var codeString = IssueCodes.toString(code);
        LOG.warn("Adding the following warning to job {}'s media {}: {} - {}", jobId, mediaId, codeString, message);

        getJobImpl(jobId).addWarning(mediaId, IssueSources.toString(source), codeString, message);
    }


    public synchronized void addJobError(long jobId, IssueCodes code, String message) {
        addError(jobId, 0, code, message);
    }

    public synchronized void addError(long jobId, long mediaId, IssueCodes code, String message) {
        addError(jobId, mediaId, code, message, IssueSources.WORKFLOW_MANAGER);
    }

    public synchronized void addError(long jobId, long mediaId, IssueCodes code, String message, IssueSources source) {
        var codeString = IssueCodes.toString(code);

        LOG.error("Adding the following error to job {}'s media {}: {} - {}", jobId, mediaId, codeString, message);

        getJobImpl(jobId).addError(mediaId, IssueSources.toString(source), codeString, message);

        if (source != IssueSources.MARKUP && mediaId != 0) {
            getMediaImpl(jobId, mediaId).setFailed(true);
        }
    }


    public synchronized void addDetectionProcessingError(DetectionProcessingError error) {
        LOG.info("Adding detection processing error for job {}'s media {}: {} - {}",
                 error.getJobId(), error.getMediaId(), error.getErrorCode(), error.getErrorMessage());
        getJobImpl(error.getJobId()).addDetectionProcessingError(error);

        var media = getMediaImpl(error.getJobId(), error.getMediaId());
        media.setFailed(true);
    }


    public synchronized Multimap<Long, JsonIssueDetails> getMergedDetectionErrors(long jobId) {
        return DetectionErrorUtil.getMergedDetectionErrors(getJob(jobId));
    }


    public synchronized void setJobStatus(long jobId, BatchJobStatusType batchJobStatusType) {
        var job = getJobImpl(jobId);
        if (job.getStatus() != batchJobStatusType) {
            LOG.info("Setting status of job {} to {}", jobId, batchJobStatusType);
            getJobImpl(jobId).setStatus(batchJobStatusType);
        }
    }


    public synchronized void incrementTask(long jobId) {
        var job = getJobImpl(jobId);
        int currentTask = job.getCurrentTaskIndex();
        int nextTask = currentTask + 1;
        LOG.info("Changing job {}'s current task index from {} to {}", jobId, currentTask, nextTask);
        job.setCurrentTaskIndex(nextTask);
    }



    private static final Set<UriScheme> SUPPORTED_URI_SCHEMES = EnumSet.of(UriScheme.FILE, UriScheme.HTTP,
                                                                           UriScheme.HTTPS);
    private static final String NOT_DEFINED_URI_SCHEME = "URI scheme not defined";
    private static final String NOT_SUPPORTED_URI_SCHEME = "Unsupported URI scheme";
    private static final String LOCAL_FILE_DOES_NOT_EXIST = "File does not exist";
    private static final String LOCAL_FILE_NOT_READABLE = "File is not readable";


    public synchronized MediaImpl initMedia(String uriStr, Map<String, String> mediaSpecificProperties,
                                        Map<String, String> providedMetadataProperties) {
        long mediaId = IdGenerator.next();
        LOG.info("Initializing media from {} with id {}", uriStr, mediaId);

        try {
            URI uri = new URI(uriStr);
            UriScheme uriScheme = UriScheme.parse(uri.getScheme());
            String errorMessage = null;
            Path localPath = null;

            if (uriScheme == UriScheme.UNDEFINED) {
                errorMessage = NOT_DEFINED_URI_SCHEME;
            }
            else if (!SUPPORTED_URI_SCHEMES.contains(uriScheme)) {
                errorMessage = NOT_SUPPORTED_URI_SCHEME;
            }
            else if (uriScheme == UriScheme.FILE) {
                localPath = Paths.get(uri).toAbsolutePath();
                errorMessage = checkForLocalFileError(localPath);
            }
            else {
                localPath = _propertiesUtil.getTemporaryMediaDirectory()
                        .toPath()
                        .resolve(UUID.randomUUID().toString())
                        .toAbsolutePath();
            }

            return new MediaImpl(mediaId, uriStr, uriScheme, localPath, mediaSpecificProperties,
                                 providedMetadataProperties, errorMessage);
        }
        catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e) {
            return new MediaImpl(mediaId, uriStr, UriScheme.UNDEFINED, null,
                                 mediaSpecificProperties, providedMetadataProperties, e.getMessage());
        }
    }

    private static String checkForLocalFileError(Path path) {
        if (Files.notExists(path)) {
            return LOCAL_FILE_DOES_NOT_EXIST;
        }
        if (!Files.isReadable(path)) {
            return LOCAL_FILE_NOT_READABLE;
        }
        return null;
    }


    public synchronized void addMediaInspectionInfo(
            long jobId, long mediaId, String sha256, MediaType mediaType, String mimeType, int length,
            Map<String, String> metadata) {
        LOG.info("Adding media metadata to job {}'s media {}.", jobId, mediaId);
        MediaImpl media = getMediaImpl(jobId, mediaId);
        media.setSha256(sha256);
        media.setType(mediaType);
        media.setMimeType(mimeType);
        media.setLength(length);
        media.addMetadata(metadata);
    }

    public synchronized void addConvertedMediaPath(long jobId, long mediaId,
                                                   Path convertedMediaPath) {
        LOG.info("Setting job {}'s media {}'s converted media path to {}",
                 jobId, mediaId, convertedMediaPath);
        getMediaImpl(jobId, mediaId).setConvertedMediaPath(convertedMediaPath);
    }

    public synchronized void addFrameTimeInfo(long jobId, long mediaId,
                                              FrameTimeInfo frameTimeInfo) {
        LOG.info("Adding frame time info to job {}'s media {}.", jobId, mediaId);
        getMediaImpl(jobId, mediaId).setFrameTimeInfo(frameTimeInfo);
    }

    private MediaImpl getMediaImpl(long jobId, long mediaId) {
        MediaImpl media = getJobImpl(jobId).getMedia(mediaId);
        if (media == null) {
            throw new IllegalArgumentException(String.format("Job %s does not have media with id %s", jobId, mediaId));
        }
        return media;
    }
}
