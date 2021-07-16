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

package org.mitre.mpf.wfm.camel.operations.mediaretrieval;

import org.apache.camel.Exchange;
import org.apache.commons.io.FileUtils;
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.camel.WfmProcessor;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.mitre.mpf.wfm.enums.IssueCodes;
import org.mitre.mpf.wfm.enums.MpfHeaders;
import org.mitre.mpf.wfm.service.S3StorageBackend;
import org.mitre.mpf.wfm.service.StorageException;
import org.mitre.mpf.wfm.util.AggregateJobPropertiesUtil;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.function.Function;

/** This processor downloads a file from a remote URI to the local filesystem. */
@Component(RemoteMediaProcessor.REF)
public class RemoteMediaProcessor extends WfmProcessor {
    public static final String REF = "remoteMediaProcessor";
    private static final Logger log = LoggerFactory.getLogger(RemoteMediaProcessor.class);

    private final InProgressBatchJobsService _inProgressJobs;

    private final S3StorageBackend _s3Service;

    private final PropertiesUtil _propertiesUtil;

    private final AggregateJobPropertiesUtil _aggregateJobPropertiesUtil;

    @Inject
    public RemoteMediaProcessor(
            InProgressBatchJobsService inProgressJobs,
            S3StorageBackend s3Service,
            PropertiesUtil propertiesUtil,
            AggregateJobPropertiesUtil aggregateJobPropertiesUtil) {
        _inProgressJobs = inProgressJobs;
        _s3Service = s3Service;
        _propertiesUtil = propertiesUtil;
        _aggregateJobPropertiesUtil = aggregateJobPropertiesUtil;
    }


    @Override
    public void wfmProcess(Exchange exchange) throws WfmProcessingException {

        long jobId = exchange.getIn().getHeader(MpfHeaders.JOB_ID, Long.class);
        long mediaId = exchange.getIn().getHeader(MpfHeaders.MEDIA_ID, Long.class);

        BatchJob job = _inProgressJobs.getJob(jobId);
        Media media = job.getMedia(mediaId);
        log.debug("Retrieving {} and saving it to `{}`.", media.getUri(), media.getLocalPath());

        switch(media.getUriScheme()) {
            case FILE:
                // Do nothing.
                break;
            case HTTP:
            case HTTPS:
                try {
                    Function<String, String> combinedProperties = _aggregateJobPropertiesUtil
                            .getCombinedProperties(job, media);
                    if (S3StorageBackend.requiresS3MediaDownload(combinedProperties)) {
                        _s3Service.downloadFromS3(media, combinedProperties);
                    }
                    else {
                        downloadFile(jobId, media);
                    }
                    media.getLocalPath().toFile().deleteOnExit();
                }
                catch (StorageException e) {
                    String message = handleMediaRetrievalException(
                            media, media.getLocalPath().toFile(), e);
                    handleMediaRetrievalFailure(jobId, media, message);
                }
                break;
            default:
                log.warn("The UriScheme '{}' was not expected at this time.", media.getUriScheme());
                _inProgressJobs.addError(jobId, mediaId, IssueCodes.REMOTE_STORAGE_DOWNLOAD, String.format(
                        "The scheme '%s' was not expected or does not have a handler associated with it.",
                        media.getUriScheme()));
                break;
        }

        exchange.getOut().setHeader(MpfHeaders.CORRELATION_ID, exchange.getIn().getHeader(MpfHeaders.CORRELATION_ID));
        exchange.getOut().setHeader(MpfHeaders.SPLIT_SIZE, exchange.getIn().getHeader(MpfHeaders.SPLIT_SIZE));
        exchange.getOut().setHeader(MpfHeaders.JMS_PRIORITY, exchange.getIn().getHeader(MpfHeaders.JMS_PRIORITY));
        exchange.getOut().setHeader(MpfHeaders.MEDIA_ID, mediaId);
    }


    private void downloadFile(long jobId, Media media) {
        File localFile = null;
        for (int i = 0; i <= _propertiesUtil.getRemoteMediaDownloadRetries(); i++) {
            String errorMessage;
            try {
                localFile = media.getLocalPath().toFile();
                FileUtils.copyURLToFile(new URL(media.getUri()), localFile);
                log.debug("Successfully retrieved {} and saved it to '{}'.", media.getUri(), media.getLocalPath());
                break;
            } catch (IOException e) { // "javax.net.ssl.SSLException: SSL peer shut down incorrectly" has been observed.
                errorMessage = handleMediaRetrievalException(media, localFile, e);
            } catch (Exception e) { // specifying "http::" will cause an IllegalArgumentException
                errorMessage = handleMediaRetrievalException(media, localFile, e);
                handleMediaRetrievalFailure(jobId, media, errorMessage);
                break; // exception is not recoverable
            }

            if (i < _propertiesUtil.getRemoteMediaDownloadRetries()) {
                try {
                    int sleepMillisec = _propertiesUtil.getRemoteMediaDownloadSleep() * (i + 1);
                    log.warn("Sleeping for {} ms before trying to retrieve {} again.", sleepMillisec, media.getUri());
                    Thread.sleep(sleepMillisec);
                } catch (InterruptedException e) {
                    log.warn("Sleep interrupted.");
                    Thread.currentThread().interrupt();
                    break; // abort download attempt
                }
            } else {
                handleMediaRetrievalFailure(jobId, media, errorMessage);
            }
        }
    }


    private static void deleteOrLeakFile(File file) {
        try {
            if(file != null) {
                file.delete();
            }
        } catch(Exception exception) {
            log.warn("Failed to delete the local file '{}'. If it exists, it must be deleted manually.", file);
        }
    }

    private static String handleMediaRetrievalException(Media media, File localFile, Exception e) {
        log.warn("Failed to retrieve {}.", media.getUri(), e);
        // Try to delete the local file, but do not throw an exception if this operation fails.
        deleteOrLeakFile(localFile);
        return e.toString();
    }

    private void handleMediaRetrievalFailure(long jobId, Media media,
                                             String errorMessage) {
        _inProgressJobs.addError(jobId, media.getId(), IssueCodes.REMOTE_STORAGE_DOWNLOAD,
                                 "Error retrieving media and saving it to temp file: " + errorMessage);
    }
}
