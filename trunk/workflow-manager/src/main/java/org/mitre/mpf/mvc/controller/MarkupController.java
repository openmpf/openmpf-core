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

package org.mitre.mpf.mvc.controller;

import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.mitre.mpf.mvc.util.ModelUtils;
import org.mitre.mpf.mvc.util.NIOUtils;
import org.mitre.mpf.rest.api.MarkupPageListModel;
import org.mitre.mpf.rest.api.MarkupResultConvertedModel;
import org.mitre.mpf.rest.api.MarkupResultModel;
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.data.access.JobRequestDao;
import org.mitre.mpf.wfm.data.access.MarkupResultDao;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;
import org.mitre.mpf.wfm.data.entities.persistent.JobRequest;
import org.mitre.mpf.wfm.data.entities.persistent.MarkupResult;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.mitre.mpf.wfm.service.S3StorageBackend;
import org.mitre.mpf.wfm.service.StorageException;
import org.mitre.mpf.wfm.util.AggregateJobPropertiesUtil;
import org.mitre.mpf.wfm.util.IoUtils;
import org.mitre.mpf.wfm.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

@Api(value = "Markup", description = "Access the information of marked up media")
@Controller
@Scope("request")
public class MarkupController {
    private static final Logger log = LoggerFactory.getLogger(MarkupController.class);

    @Autowired
    private MarkupResultDao markupResultDao;

    @Autowired
    private JobRequestDao jobRequestDao;

    @Autowired
    private JsonUtils jsonUtils;

    @Autowired
    private S3StorageBackend s3StorageBackend;

    @Autowired
    private AggregateJobPropertiesUtil aggregateJobPropertiesUtil;

    private List<MarkupResultModel> getMarkupResultsJson(Long jobId) {
        //all MarkupResult objects
        List<MarkupResultModel> markupResultModels = new ArrayList<MarkupResultModel>();
        for (MarkupResult markupResult : markupResultDao.findAll()) {
            if (jobId != null) {
                if (markupResult.getJobId() == jobId) {
                    markupResultModels.add(ModelUtils.converMarkupResult(markupResult));
                }
            } else {
                markupResultModels.add(ModelUtils.converMarkupResult(markupResult));
            }
        }
        return markupResultModels;
    }

    @RequestMapping(value = "/markup/results", method = RequestMethod.GET)
    @ResponseBody
    public List<MarkupResultModel> getMarkupResultsJsonSession(@ApiParam(value = "Job id - OPTIONAL") @RequestParam(value = "jobId", required = false) Long jobId) {
        return getMarkupResultsJson(jobId);
    }

    //https://datatables.net/manual/server-side#Sent-parameters
    //draw is the counter of how many times it has called back
    //length is how many to return
    //start is offset from 0
    //search is string to filter
    @RequestMapping(value = {"/markup/get-markup-results-filtered"}, method = RequestMethod.POST)
    @ResponseBody
    public MarkupPageListModel getMarkupResultsFiltered(@RequestParam(value = "jobId", required = true) long jobId,
                                           @RequestParam(value = "draw", required = false) int draw,
                                           @RequestParam(value = "start", required = false) int start,
                                           @RequestParam(value = "length", required = false) int length,
                                           @RequestParam(value = "search", required = false) String search,
                                           @RequestParam(value = "sort", required = false) String sort) throws WfmProcessingException {
        log.debug("get-markup-results-filtered Params jobId: {}, draw:{}, start:{},length:{},search:{}, sort:{} ", jobId, draw, start, length, search, sort);

        //all MarkupResult objects
        List<MarkupResult> markupResults = markupResultDao.findByJobId(jobId);
        Collections.reverse(markupResults);

        //convert markup objects
        List<MarkupResultConvertedModel> markupResultModels = new ArrayList<MarkupResultConvertedModel>();
        for (MarkupResult markupResult : markupResults) {
            MarkupResultConvertedModel model = ModelUtils.convertMarkupResultWithContentType(markupResult);
            markupResultModels.add(model);
        }

        //add job media that may exist without markup
        JobRequest jobRequest = jobRequestDao.findById(jobId);
        if (jobRequest != null) {
            BatchJob job = jsonUtils.deserialize(jobRequest.getJob(), BatchJob.class);

            for (Media med : job.getMedia()) {
                MarkupResultConvertedModel model = new MarkupResultConvertedModel();
                model.setJobId(jobId);
                model.setPipeline(job.getPipelineElements().getName());
                model.setSourceUri(med.getUri());
                model.setSourceFileAvailable(false);
                if (med.getUri() != null) {
                    Path path = IoUtils.toLocalPath(med.getUri()).orElse(null);
                    if (path == null || Files.exists(path)) {
                        String downloadUrl = UriComponentsBuilder.fromPath("server/download")
                                .queryParam("sourceUri", med.getUri())
                                .queryParam("jobId", jobId)
                                .toUriString();
                        model.setSourceDownloadUrl(downloadUrl);
                        model.setSourceFileAvailable(true);
                    }
                    if (path != null && Files.exists(path)) {
                        model.setSourceURIContentType(NIOUtils.getPathContentType(path));
                        model.setSourceImgUrl("server/node-image?nodeFullPath=" + path);
                    }
                }
                //add to the list
                boolean found = false;
                for (MarkupResultConvertedModel existing : markupResultModels) {
                    if (existing.getSourceUri().equals(model.getSourceUri())) found = true;
                }
                if (!found) markupResultModels.add(model);
            }
        }

        //handle search
        List<MarkupResultConvertedModel> markupResultModelsFinal = new ArrayList<MarkupResultConvertedModel>();
        for (MarkupResultConvertedModel markupResult : markupResultModels) {
            if (search != null && search.length() > 0) {
                search = search.toLowerCase();
                if ((markupResult.getJobId() + "").toLowerCase().contains(search) ||
                        (markupResult.getMarkupUri() != null && markupResult.getMarkupUri().toLowerCase().contains(search)) ||
                        (markupResult.getSourceUri() != null && markupResult.getSourceUri().toLowerCase().contains(search))) {
                    markupResultModelsFinal.add(markupResult);
                }
            } else {
                markupResultModelsFinal.add(markupResult);
            }
        }

        int records_total = markupResultModelsFinal.size();
        int records_filtered = records_total;// Total records, after filtering (i.e. the total number of records after filtering has been applied - not just the number of records being returned for this page of data).

        //handle paging
        int end = start + length;
        end = (end > markupResultModelsFinal.size()) ? markupResultModelsFinal.size() : end;
        start = (start <= end) ? start : end;
        List<MarkupResultConvertedModel> modelsFiltered = markupResultModelsFinal.subList(start,end);

        //build output
        String error = null;
        MarkupPageListModel model = new MarkupPageListModel(draw,records_total,records_filtered,error,modelsFiltered);

        return model;
    }

    @RequestMapping(value = "/markup/content", method = RequestMethod.GET, produces = MediaType.IMAGE_JPEG_VALUE)
    @ResponseBody
    public void serve(HttpServletResponse response, @RequestParam(value = "id", required = true) long id) throws IOException, URISyntaxException {
        MarkupResult mediaMarkupResult = markupResultDao.findById(id);
        if (mediaMarkupResult != null) {
            //only on image!
            if (!StringUtils.endsWithIgnoreCase(mediaMarkupResult.getMarkupUri(), "avi")) {
                String nonUrlPath = mediaMarkupResult.getMarkupUri().replace("file:", "");
                File f = new File(nonUrlPath);
                if (f.canRead()) {
                    FileUtils.copyFile(f, response.getOutputStream());
                    response.flushBuffer();
                }
            }
        }
        //TODO need a no image available if final nested if is not met
    }


    @RequestMapping(value = "/markup/download", method = RequestMethod.GET)
    public void getFile(@RequestParam("id") long id, HttpServletResponse response) throws IOException, StorageException {
        MarkupResult mediaMarkupResult = markupResultDao.findById(id);
        if (mediaMarkupResult == null) {
            log.debug("server download file failed for markup id = " +id);
            response.setStatus(404);
            response.flushBuffer();
            return;
        }

        Path localPath = IoUtils.toLocalPath(mediaMarkupResult.getMarkupUri()).orElse(null);
        if (localPath != null) {
            if (!Files.exists(localPath)) {
                log.debug("server download file failed for markup id = " + id);
                response.setStatus(404);
                response.flushBuffer();
                return;
            }
            IoUtils.writeFileAsAttachment(localPath, response);
            return;
        }

        Function<String, String> combinedProperties = getProperties(mediaMarkupResult);

        if (S3StorageBackend.requiresS3ResultUpload(combinedProperties)) {
            S3Object s3Object = s3StorageBackend.getFromS3(mediaMarkupResult.getMarkupUri(), combinedProperties);
            try (InputStream inputStream = s3Object.getObjectContent()) {
                ObjectMetadata metadata = s3Object.getObjectMetadata();
                IoUtils.writeContentAsAttachment(inputStream, response, s3Object.getKey(), metadata.getContentType(),
                                                 metadata.getContentLength());
            }
            return;
        }

        URL mediaUrl = IoUtils.toUrl(mediaMarkupResult.getMarkupUri());
        int slashPos = mediaUrl.getPath().lastIndexOf('/');
        String fileName = mediaUrl.getPath().substring(1 + slashPos);
        if (fileName.isEmpty()) {
            fileName = "markup_file";
        }
        URLConnection urlConnection = mediaUrl.openConnection();
        try (InputStream inputStream = urlConnection.getInputStream()) {
            IoUtils.writeContentAsAttachment(inputStream, response, fileName, urlConnection.getContentType(),
                                             urlConnection.getContentLength());
        }
    }

    private Function<String, String> getProperties(MarkupResult markupResult) {
        BatchJob job = Optional.ofNullable(jobRequestDao.findById(markupResult.getJobId()))
                .map(JobRequest::getJob)
                .map(bytes -> jsonUtils.deserialize(bytes, BatchJob.class))
                .orElse(null);

        if (job == null) {
            return x -> null;
        }

        var media = job.getMedia()
                .stream()
                .filter(m -> URI.create(m.getUri()).equals(URI.create(markupResult.getSourceUri())))
                .findAny()
                .orElse(null);

        var action = job.getPipelineElements().getAction(markupResult.getTaskIndex(), markupResult.getActionIndex());

        return aggregateJobPropertiesUtil.getCombinedProperties(job, media, action);
    }
}
