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

package org.mitre.mpf.wfm.camel.operations.markup;

import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.buffers.Markup;
import org.mitre.mpf.wfm.camel.ResponseProcessor;
import org.mitre.mpf.wfm.camel.operations.detection.DetectionResponseProcessor;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.access.MarkupResultDao;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;
import org.mitre.mpf.wfm.data.entities.persistent.MarkupResult;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.mitre.mpf.wfm.enums.IssueCodes;
import org.mitre.mpf.wfm.enums.IssueSources;
import org.mitre.mpf.wfm.enums.MarkupStatus;
import org.mitre.mpf.wfm.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component(MarkupResponseProcessor.REF)
public class MarkupResponseProcessor extends ResponseProcessor<Markup.MarkupResponse> {
    public static final String REF = "markupResponseProcessor";
    private static final Logger log = LoggerFactory.getLogger(DetectionResponseProcessor.class);

    public MarkupResponseProcessor() {
        super(Markup.MarkupResponse.class);
    }

    @Autowired
    private InProgressBatchJobsService inProgressJobs;

    @Autowired
    private MarkupResultDao markupResultDao;

    @Autowired
    private StorageService storageService;

    @Override
    public Object processResponse(long jobId, Markup.MarkupResponse markupResponse, Map<String, Object> headers) throws WfmProcessingException {
        log.debug("[Job {}:{}:{}] Received response for Media {} (Index = {}). Error? {}", jobId, markupResponse.getTaskIndex(), markupResponse.getActionIndex(), markupResponse.getMediaId(), markupResponse.getMediaIndex(), markupResponse.getHasError() ? markupResponse.getErrorMessage() : "None.");
        MarkupResult markupResult = new MarkupResult();
        markupResult.setTaskIndex(markupResponse.getTaskIndex());
        markupResult.setActionIndex(markupResponse.getActionIndex());
        markupResult.setMediaId(markupResponse.getMediaId());
        markupResult.setMediaIndex(markupResponse.getMediaIndex());
        markupResult.setJobId(jobId);
        markupResult.setMarkupStatus(markupResponse.getHasError() ? MarkupStatus.FAILED : MarkupStatus.COMPLETE);
        markupResult.setMarkupUri(markupResponse.getOutputFileUri());
        markupResult.setMessage(markupResponse.hasErrorMessage() ? markupResponse.getErrorMessage() : null);

        BatchJob job = inProgressJobs.getJob(jobId);
        Media media = job.getMedia(markupResponse.getMediaId());
        markupResult.setPipeline(job.getPipelineElements().getName());
        markupResult.setSourceUri(media.getUri());

        storageService.store(markupResult);
        markupResultDao.persist(markupResult);

        if (markupResult.getMarkupStatus() == MarkupStatus.FAILED) {
            var errorMessage = markupResponse.hasErrorMessage()
                    ? markupResponse.getErrorMessage()
                    : "FAILED";
            inProgressJobs.addError(jobId, markupResult.getMediaId(), IssueCodes.MARKUP, errorMessage,
                                    IssueSources.MARKUP);
        }
        if (markupResult.getMarkupStatus() == MarkupStatus.COMPLETE_WITH_WARNING) {
            var warningMessage = markupResponse.hasErrorMessage()
                    ? markupResponse.getErrorMessage()
                    : "COMPLETE_WITH_WARNING";
            inProgressJobs.addWarning(jobId, markupResult.getMediaId(), IssueCodes.MARKUP,
                                      warningMessage, IssueSources.MARKUP);
        }
        return null;
    }
}
