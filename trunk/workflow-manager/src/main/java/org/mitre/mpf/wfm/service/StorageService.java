/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2018 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2018 The MITRE Corporation                                       *
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


package org.mitre.mpf.wfm.service;

import com.google.common.collect.ImmutableList;
import org.mitre.mpf.interop.JsonOutputObject;
import org.mitre.mpf.wfm.camel.operations.detection.artifactextraction.ArtifactExtractionRequest;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.persistent.MarkupResult;
import org.mitre.mpf.wfm.enums.MarkupStatus;
import org.mitre.mpf.wfm.enums.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

@Service
public class StorageService {

    private static final Logger LOG = LoggerFactory.getLogger(StorageService.class);

    private final InProgressBatchJobsService _inProgressJobs;

    private final List<StorageBackend> _remoteBackends;

    private final LocalStorageBackend _localBackend;

    @Inject
    StorageService(
            InProgressBatchJobsService inProgressJobs,
            S3StorageBackend s3StorageBackend,
            CustomNginxStorageBackend nginxStorageBackend,
            LocalStorageBackend localStorageBackend) {

        _inProgressJobs = inProgressJobs;
        _remoteBackends = ImmutableList.of(s3StorageBackend, nginxStorageBackend);
        _localBackend = localStorageBackend;
    }


    public URI store(JsonOutputObject outputObject) throws IOException {
        Exception remoteException = null;
        try {
            for (StorageBackend remoteBackend : _remoteBackends) {
                if (remoteBackend.canStore(outputObject)) {
                    return remoteBackend.store(outputObject);
                }
            }
        }
        catch (StorageException ex) {
            remoteException = ex;
            LOG.warn(String.format(
                    "Failed to remotely store output object for job id %s. It will be stored locally instead.",
                    outputObject.getJobId()), ex);
            outputObject.getJobWarnings().add(
                    "This output object was stored locally because storing it remotely failed due to: " + ex);
        }

        try {
            return _localBackend.store(outputObject);
        }
        catch (IOException localException) {
            if (remoteException != null) {
                localException.addSuppressed(remoteException);
            }
            throw localException;
        }
    }


    public Map<Integer, URI> storeVideoArtifacts(ArtifactExtractionRequest request) throws IOException {
        if (request.getMediaType() != MediaType.VIDEO) {
            throw new IllegalArgumentException("Expected media type of VIDEO, but it was " + request.getMediaType());
        }

        Exception remoteException = null;
        try {
            for (StorageBackend remoteBackend : _remoteBackends) {
                if (remoteBackend.canStore(request)) {
                    return remoteBackend.storeVideoArtifacts(request);
                }
            }
        }
        catch (IOException | StorageException ex) {
            remoteException = ex;
            handleRemoteStorageFailure(request, remoteException);
        }

        try {
            return _localBackend.storeVideoArtifacts(request);
        }
        catch (IOException localException) {
            if (remoteException != null) {
                localException.addSuppressed(remoteException);
            }
            throw localException;
        }
    }


    public URI storeImageArtifact(ArtifactExtractionRequest request) throws IOException {
        if (request.getMediaType() != MediaType.IMAGE) {
            throw new IllegalArgumentException("Expected media type of IMAGE, but it was " + request.getMediaType());
        }

        Exception remoteException = null;
        try {
            for (StorageBackend remoteBackend : _remoteBackends) {
                if (remoteBackend.canStore(request)) {
                    return remoteBackend.storeImageArtifact(request);
                }
            }
        }
        catch (IOException | StorageException ex) {
            remoteException = ex;
            handleRemoteStorageFailure(request, remoteException);
        }

        try {
            return _localBackend.storeImageArtifact(request);
        }
        catch (IOException localException) {
            if (remoteException != null) {
                localException.addSuppressed(remoteException);
            }
            throw localException;
        }
    }


    private void handleRemoteStorageFailure(ArtifactExtractionRequest request, Exception e) {
        LOG.warn(String.format("Failed to store artifact for job id %s. It will be stored locally instead.",
                               request.getJobId()), e);
        _inProgressJobs.addJobWarning(
                request.getJobId(),
                "Artifacts were stored locally because storing them remotely failed due to: " + e);
    }


    public void store(MarkupResult markupResult) {
        try {
            for (StorageBackend remoteBackend : _remoteBackends) {
                if (remoteBackend.canStore(markupResult)) {
                    remoteBackend.store(markupResult);
                    return;
                }
            }
        }
        catch (IOException | StorageException ex) {
            LOG.warn(String.format(
                    "Failed to remotely store markup for job id %s. It will be stored locally instead.",
                    markupResult.getJobId()), ex);


            String message = "Markup was stored locally because storing it remotely failed due to: " + ex;
            String existingMessage = markupResult.getMessage();
            if (existingMessage != null && !existingMessage.isEmpty()) {
                message = existingMessage + "; " + message;
            }
            markupResult.setMessage(message);
            markupResult.setMarkupStatus(MarkupStatus.COMPLETE_WITH_WARNING);
            _inProgressJobs.addJobWarning(markupResult.getJobId(), message);
        }
        _localBackend.store(markupResult);
    }
}
