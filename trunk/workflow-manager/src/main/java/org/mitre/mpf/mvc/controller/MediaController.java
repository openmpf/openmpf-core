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

import io.swagger.annotations.Api;
import org.apache.commons.io.FileUtils;
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.service.ServerMediaService;
import org.mitre.mpf.wfm.util.IoUtils;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.*;


@Api( value = "Media",
	description = "Media processing")
@Controller
@Scope("request")
public class MediaController {
	private static final Logger log = LoggerFactory.getLogger(MediaController.class);

	@Autowired
	private PropertiesUtil propertiesUtil;

	@Autowired
	private IoUtils ioUtils;

	@Autowired
	private ServerMediaService serverMediaService;

	@RequestMapping(value = "/upload/max-file-upload-cnt", method = RequestMethod.GET)
	@ResponseBody
	public String getWebMaxFileUploadCnt() {
		return Integer.toString(propertiesUtil.getWebMaxFileUploadCnt());
	}

	@RequestMapping(value = "/saveURL", method = RequestMethod.POST)
	@ResponseBody
	public Map<String, String> saveMedia(HttpServletRequest request, @RequestParam(value="urls", required=true) String[] urls,
										 @RequestParam(value="desiredpath", required=true) String desiredpath,
										 HttpServletResponse response) throws WfmProcessingException {
		log.debug("URL Upload to Directory:"+desiredpath+" urls:"+urls.length);

		String err = "Illegal or missing desiredpath";
		if(desiredpath == null){
			log.error(err);
			throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR,err);
		};
		String remoteMediaDirectory = propertiesUtil.getRemoteMediaDirectory().getAbsolutePath();
		//verify the desired path
		File desiredPath = new File(desiredpath);
		if (!desiredPath.exists() || !desiredPath.getAbsolutePath().startsWith(remoteMediaDirectory)) {//make sure it is valid and within the remote-media directory
			log.error(err);
			throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, err);
		}

		//passing in urls as a list of Strings
		//download the media to the server
		//build a map of success or failure for each file with a custom response object
		Map<String, String> urlResultMap = new HashMap<>();
		List<File> successFiles = new ArrayList<>();

		for(String enteredURL : urls) {
			enteredURL = enteredURL.trim();
			URI uri;
			try {
				uri = new URI(enteredURL);
				//the check for absolute URI determines if any scheme is present, regardless of validity
				//(which is checked in both this and the next try block)
				if (!uri.isAbsolute()) {
					uri = new URI("http://" + uri.toASCIIString());
				}
			} catch (URISyntaxException incorrectUriTranslation) {
				log.error("The string {} did not translate cleanly to a URI.", enteredURL, incorrectUriTranslation);
				urlResultMap.put(enteredURL, "String did not cleanly convert to URI");
				continue;
			}
			File newFile = null;
			String localName = null;
			try {
				URL url = uri.toURL(); //caught by MalformedURLException
				//will throw an IOException,which is already caught
				HttpURLConnection connection = (HttpURLConnection) url.openConnection();
				connection.setRequestMethod("HEAD");
				connection.connect();
				connection.disconnect();

				String filename = url.getFile();
				if (filename.isEmpty()) {
					String err2 = "The filename does not exist when uploading from the url '" + url + "'";
					log.error(err2);
					urlResultMap.put(enteredURL, err2);
					continue;
				}

				if (!ioUtils.isApprovedFile(url)) {
					String contentType = ioUtils.getMimeType(url);
					String msg = "The media is not a supported type. Please add a whitelist."+contentType+" entry to the mediaType.properties file.";
					log.error(msg+" URL:"+url);
					urlResultMap.put(enteredURL,msg);
					continue;
				}

				localName = uri.getPath();
				//we consider no path to be malformed for our purposes
				if (localName.isEmpty()) {
					throw new MalformedURLException(String.format("%s does not have valid path", uri));
				}

				//use the full path name for the filename to allow for more unique filenames
				localName = localName.substring(1);//remove the leading '/'
				localName = localName.replace("/", "-");//replace the rest of the path with -

				//get a new unique filename in case the name currently exists
				newFile = ioUtils.getNewFileName(desiredpath,localName);

				//save the file
				FileUtils.copyURLToFile(url, newFile);
				log.info("Completed write of {} to {}", uri.getPath(), newFile.getAbsolutePath());
				urlResultMap.put(enteredURL, "successful write to: " + newFile.getAbsolutePath());
				successFiles.add(newFile);
			} catch (MalformedURLException badUrl) {
				log.error("URI {} could not be converted. ", uri, badUrl);
				urlResultMap.put(enteredURL, "Unable to locate media at the provided address.");
			} catch (IOException badWrite) {
				log.error("Error writing media to temp file from {}.", enteredURL, badWrite);
				urlResultMap.put(enteredURL, "Unable to save media from this url. Please view the server logs for more information.");
				if (newFile != null && newFile.exists()) {
					newFile.delete();
				}
			} catch (Exception failure) { //catch the remaining exceptions
				//this is most likely a failed connection
				log.error("Exception thrown while saving media from the url {}.", enteredURL, failure);
				urlResultMap.put(enteredURL, "Error while saving media from this url. Please view the server logs for more information.");
			}
		}

		serverMediaService.addFilesToCache(desiredpath, successFiles, request.getServletContext());

		return urlResultMap;
	}

	/// Dropzone uploads one file at a time
	//@CrossOrigin
	@RequestMapping(value = "/uploadFile", method = RequestMethod.POST)
	public ResponseEntity saveMediaFileUpload(MultipartHttpServletRequest request, HttpServletResponse response) throws WfmProcessingException {
		log.debug("[saveMediaFileUpload]");
		File newFile = null;
		String desiredPathParam = request.getParameter("desiredpath");
		log.debug("Upload to Directory:"+desiredPathParam);
		if(desiredPathParam == null) return new ResponseEntity<>("{\"error\":\"desiredPathParam Empty\"}", HttpStatus.INTERNAL_SERVER_ERROR);
		String remoteMediaDirectory = propertiesUtil.getRemoteMediaDirectory().getAbsolutePath();

		try {
			//verify the desired path
			File desiredPath = new File(desiredPathParam);
			if(!desiredPath.exists() || !desiredPath.getAbsolutePath().startsWith(remoteMediaDirectory)) {//make sure it is valid and within the remote-media directory
				String err = "Error with desired path: "+desiredPathParam;
				log.error(err);
				return new ResponseEntity<>("{\"error\":\"" + err + "\"}", HttpStatus.INTERNAL_SERVER_ERROR);
			}

			Iterator<String> itr = request.getFileNames();
			while (itr.hasNext()) {
				String uploadedFile = itr.next();
				MultipartFile file = request.getFile(uploadedFile);
				String filename = file.getOriginalFilename();
				byte[] bytes = file.getBytes();
				String contentType = ioUtils.getMimeType(bytes);

				log.debug("[saveMediaFileUpload] File:" + filename + "  ContentType:" + contentType + " Size:" + bytes.length);

				if (filename.isEmpty()) {
					String err = "The filename is empty during upload of the MultipartFile.";
					log.error(err);
					return new ResponseEntity<>("{\"error\":\"" + err + "\"}", HttpStatus.INTERNAL_SERVER_ERROR);
				}

				//return error if the file has an invalid content type
				if(!ioUtils.isApprovedContentType(contentType)){
					String msg = "The media is not a supported type. Please add a whitelist."+contentType+" entry to the mediaType.properties file.";
					log.error(msg+" File:"+filename);
					return new ResponseEntity<>("{\"error\":\"" + msg + "\"}", HttpStatus.INTERNAL_SERVER_ERROR);
				}

				//get a new filename
				newFile = ioUtils.getNewFileName(desiredPath.getAbsolutePath(), filename);

				//save the file
				BufferedOutputStream stream =  new BufferedOutputStream(new FileOutputStream(newFile));
				stream.write(bytes);
				stream.close();
				log.info("Completed upload and write of {} to {} ContentType:{}", newFile.getPath(), newFile.getAbsolutePath(),contentType);

				serverMediaService.addFileToCache(desiredPathParam, newFile, request.getServletContext());

				return new ResponseEntity<>(filename, HttpStatus.OK);
			}
		} catch (IOException badWrite) {
			String err = "Error writing media to temp file";
			log.error(err);
			return new ResponseEntity<>("{\"error\":\"" + err + "\"}", HttpStatus.INTERNAL_SERVER_ERROR);

		} catch (Exception e) {
			String err = "Unknown file upload error";
			log.error(err,e);
			return new ResponseEntity<>("{\"error\":\"" + err + "\"}", HttpStatus.INTERNAL_SERVER_ERROR);
		}
		String err = "Unknown file upload error";
		log.error(err);
		return new ResponseEntity<>("{\"error\":\"" + err + "\"}", HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@RequestMapping(value = {"/media/create-remote-media-directory"}, method = RequestMethod.POST)
	@ResponseStatus(value = HttpStatus.CREATED) //return 201 for post
	public ResponseEntity createRemoteMediaDirectory(@RequestParam("serverpath") String serverpath,HttpServletRequest request,  HttpServletResponse response){
		if(serverpath == null ) {
			return new ResponseEntity<>("{\"error\":\"invalid parameter\"}", HttpStatus.INTERNAL_SERVER_ERROR);
		}
		String uploadPath =  propertiesUtil.getRemoteMediaDirectory().getAbsolutePath();
		log.info("CreateRemoteMediaDirectory: ServerPath:" + serverpath + " uploadPath:" + uploadPath);
		if(serverpath.startsWith(uploadPath)){
			File dir = new File(serverpath);
			if(!dir.exists()){
				if(dir.mkdir()){
					log.debug("Directory added:"+dir.getAbsolutePath());
					serverMediaService.getAllDirectories(propertiesUtil.getServerMediaTreeRoot(), request.getServletContext(),false, uploadPath);//reload the directories
					return new ResponseEntity<>("{\"dir\":\""+dir.getAbsolutePath()+"\"}", HttpStatus.OK);
				}else{
					return new ResponseEntity<>("{\"error\":\"Cannot Create Folder\"}", HttpStatus.INTERNAL_SERVER_ERROR);
				}
			}else{
				return new ResponseEntity<>("{\"error\":\"Path Exists\"}", HttpStatus.INTERNAL_SERVER_ERROR);
			}
		}
		return new ResponseEntity<>("{\"error\":\"Invalid path\"}", HttpStatus.INTERNAL_SERVER_ERROR);
	}
}
