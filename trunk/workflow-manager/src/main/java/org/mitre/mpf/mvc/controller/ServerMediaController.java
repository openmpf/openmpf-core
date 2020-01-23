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
import org.apache.commons.io.FileUtils;
import org.mitre.mpf.mvc.model.DirectoryTreeNode;
import org.mitre.mpf.mvc.model.ServerMediaFile;
import org.mitre.mpf.mvc.model.ServerMediaFilteredListing;
import org.mitre.mpf.mvc.model.ServerMediaListing;
import org.mitre.mpf.wfm.data.access.JobRequestDao;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;
import org.mitre.mpf.wfm.data.entities.persistent.JobRequest;
import org.mitre.mpf.wfm.service.S3StorageBackend;
import org.mitre.mpf.wfm.service.ServerMediaService;
import org.mitre.mpf.wfm.service.StorageException;
import org.mitre.mpf.wfm.util.AggregateJobPropertiesUtil;
import org.mitre.mpf.wfm.util.IoUtils;
import org.mitre.mpf.wfm.util.JsonUtils;
import org.mitre.mpf.wfm.util.PropertiesUtil;
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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

@Api(value = "Server Media",description = "Server media retrieval")
@Controller
@Scope("request")
public class ServerMediaController {

    private static final Logger log = LoggerFactory.getLogger(ServerMediaController.class);

    @Autowired
    private PropertiesUtil propertiesUtil;

    @Autowired
    private AggregateJobPropertiesUtil aggregateJobPropertiesUtil;

    @Autowired
    private ServerMediaService serverMediaService;

    @Autowired
    private JobRequestDao jobRequestDao;

    @Autowired
    private JsonUtils jsonUtils;

    @Autowired
    private S3StorageBackend s3StorageBackend;


    private static class SortAlphabeticalCaseInsensitive implements Comparator<ServerMediaFile> {
        @Override
        public int compare(ServerMediaFile s1, ServerMediaFile s2) {
            return s1.getName().toLowerCase().compareTo(s2.getName().toLowerCase());
        }
    }

    private static class SortAlphabeticalCaseSensitive implements Comparator<ServerMediaFile> {
        @Override
        public int compare(ServerMediaFile s1, ServerMediaFile s2) {
            return s1.getName().compareTo(s2.getName());
        }
    }

    @RequestMapping(value = { "/server/get-all-directories" }, method = RequestMethod.GET)
    @ResponseBody
    public DirectoryTreeNode getAllDirectories(HttpServletRequest request, @RequestParam(required = false) Boolean useUploadRoot,
                                               @RequestParam(required = false, defaultValue = "true") boolean useCache){
        String nodePath = propertiesUtil.getServerMediaTreeRoot();

        // if useUploadRoot is set it will take precedence over nodeFullPath
        DirectoryTreeNode node = serverMediaService.getAllDirectories(nodePath, request.getServletContext(), useCache,
                                                                      propertiesUtil.getRemoteMediaDirectory().getAbsolutePath());
        if(useUploadRoot != null && useUploadRoot){
            node =  DirectoryTreeNode.find(node, propertiesUtil.getRemoteMediaDirectory().getAbsolutePath());
        }

        return node;
    }

    @RequestMapping(value = { "/server/get-all-files" }, method = RequestMethod.GET)
    @ResponseBody
    public ServerMediaListing getAllFiles(HttpServletRequest request, @RequestParam(required = true) String fullPath,
                                          @RequestParam(required = false, defaultValue = "true") boolean useCache) {
        File dir = new File(fullPath);
        if(!dir.isDirectory() && fullPath.startsWith(propertiesUtil.getServerMediaTreeRoot())) return null; // security check

        List<ServerMediaFile> mediaFiles = serverMediaService.getFiles(fullPath, request.getServletContext(), useCache, true);
        return new ServerMediaListing(mediaFiles);
    }

    //https://datatables.net/manual/server-side#Sent-parameters
    //draw is the counter of how many times it has called back
    //length is how many to return
    //start is offset from 0
    //search is string to filter
    @RequestMapping(value = { "/server/get-all-files-filtered" }, method = RequestMethod.POST)
    @ResponseBody
    public ServerMediaFilteredListing getAllFilesFiltered(HttpServletRequest request, @RequestParam(value="fullPath", required=true) String fullPath,
                                                          @RequestParam(required = false, defaultValue = "true") boolean useCache,
                                                          @RequestParam(value="draw", required=false) int draw,
                                                          @RequestParam(value="start", required=false) int start,
                                                          @RequestParam(value="length", required=false) int length,
                                                          @RequestParam(value="search", required=false) String search){
        log.debug("Params fullPath:{} draw:{} start:{} length:{} search:{} ",fullPath, draw, start, length, search);

        File dir = new File(fullPath);
        if(!dir.isDirectory() && fullPath.startsWith(propertiesUtil.getServerMediaTreeRoot())) return null; // security check

        List<ServerMediaFile> mediaFiles = serverMediaService.getFiles(fullPath, request.getServletContext(), useCache, false);

        // handle sort
        Collections.sort(mediaFiles, (new SortAlphabeticalCaseInsensitive()). // make 'A' come before 'B'
                thenComparing(new SortAlphabeticalCaseSensitive()) );         // make 'A' come before 'a'

        // handle search
        if(search != null && search.length() > 0) {
            List<ServerMediaFile> searchResults = new ArrayList<>();
            for (ServerMediaFile mediaFile : mediaFiles) {
                if (mediaFile.getName().toLowerCase().contains(search.toLowerCase())) {
                    searchResults.add(mediaFile);
                }
            }
            mediaFiles = searchResults;
        }

        // handle paging
        int end = start + length;
        end = (end > mediaFiles.size())? mediaFiles.size() : end;
        start = (start <= end)? start : end;

        return new ServerMediaFilteredListing(draw, mediaFiles.size(), mediaFiles.size(), mediaFiles.subList(start, end));
    }

    /***
     *
     * @param response
     * @param nodeFullPath
     * @throws IOException
     * @throws URISyntaxException
     */
    @RequestMapping(value = "/server/node-image", method = RequestMethod.GET, produces = MediaType.IMAGE_JPEG_VALUE)
    @ResponseBody
    public void serve(HttpServletResponse response, @RequestParam(value = "nodeFullPath", required = true) String nodeFullPath) throws IOException, URISyntaxException {
        //TODO: this set of lines is also used in the MarkupController - create a single method
        File f = new File(nodeFullPath);
        if(f.canRead()) {
            FileUtils.copyFile(f, response.getOutputStream());
            response.flushBuffer();
        } else {
            response.setStatus(404);
        }

        //TODO: add an image to return that is file not available and error retrieving file
        //to resources to use when there are issues

        //TODO: adjust the content type based on the image type
        //response.setContentLength(MediaType.);
    }



    @RequestMapping(value = "/server/download", method = RequestMethod.GET)
    @ResponseBody
    public void download(HttpServletResponse response,
                         @RequestParam("jobId") long jobId,
                         @RequestParam("sourceUri") URI sourceUri) throws IOException, StorageException {

        if ("file".equalsIgnoreCase(sourceUri.getScheme())) {
            IoUtils.writeFileAsAttachment(Paths.get(sourceUri), response);
        }

        JobRequest jobRequest = jobRequestDao.findById(jobId);
        if (jobRequest == null) {
            response.setStatus(404);
            response.flushBuffer();
            return;
        }

        var job = jsonUtils.deserialize(jobRequest.getJob(), BatchJob.class);
        Function<String, String> combinedProperties
                = aggregateJobPropertiesUtil.getCombinedProperties(job, sourceUri);
        if (S3StorageBackend.requiresS3MediaDownload(combinedProperties)) {
            S3Object s3Object = s3StorageBackend.getFromS3(sourceUri.toString(), combinedProperties);
            try (InputStream inputStream = s3Object.getObjectContent()) {
                ObjectMetadata metadata = s3Object.getObjectMetadata();
                IoUtils.writeContentAsAttachment(inputStream, response, s3Object.getKey(), metadata.getContentType(),
                                                 metadata.getContentLength());
            }
            return;
        }

        URL mediaUrl = sourceUri.toURL();
        int slashPos = mediaUrl.getPath().lastIndexOf('/');
        String fileName = mediaUrl.getPath().substring(1 + slashPos);
        if (fileName.isEmpty()) {
            fileName = "source_file";
        }
        URLConnection urlConnection = mediaUrl.openConnection();
        try (InputStream inputStream = urlConnection.getInputStream()) {
            IoUtils.writeContentAsAttachment(inputStream, response, fileName, urlConnection.getContentType(),
                                             urlConnection.getContentLength());
        }
    }
}
