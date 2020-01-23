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
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.mitre.mpf.mvc.util.ModelUtils;
import org.mitre.mpf.rest.api.InfoModel;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;


/**
 * Handles requests for the application home page.
 */
@Api( value = "Meta",
description = "Web application metadata" )
@Controller
@Scope("request")
public class HomeController
{
	@Autowired
	private ConfigurableEnvironment env;

	private static final Logger log = LoggerFactory.getLogger(HomeController.class);

	@Autowired
	private PropertiesUtil propertiesUtil;

	@Autowired
	private ModelUtils modelUtils;


	@RequestMapping(value = "/", method = RequestMethod.GET)
	public String getIndexPage() {
		log.debug("Welcome to the workflow-manager web app!");

		//experimenting with spring profiles - but it looks like the context would need refreshed
		//if set this way - left commented out for further investigation -
		//env.setActiveProfiles("default");
		//env.setActiveProfiles("website");

		//log all default profiles
		log.debug("default spring profiles length: {}", env.getDefaultProfiles().length);
		for(int i = 0; i < env.getDefaultProfiles().length; ++i) {
			log.debug("default spring profile index: {} with name: {}", i, env.getDefaultProfiles()[i]);
		}

		//log all active profiles
		log.debug("active spring profiles length: {}", env.getActiveProfiles().length);
		for(int i = 0; i < env.getActiveProfiles().length; ++i) {
			log.debug("active spring profile index: {} with name: {}", i, env.getActiveProfiles()[i]);
		}

		//web.active.profiles
		log.debug("activeProfiles property: " + propertiesUtil.getWebActiveProfiles());

		return "index";
	}

	@RequestMapping(value = "/rest/info", method = RequestMethod.GET,
			produces = "application/json;charset=UTF-8")
	@ApiOperation(value="Returns metadata about the Workflow Manager, such as version and build number",
	notes="Note that some of this information is set only during official builds; if you get a '0' or 'unknown', that indicates that this was not an official build.",
	produces="application/json", response=InfoModel.class )
	@ApiResponses(@ApiResponse(code = 200, message = "Successful response"))
	@ResponseBody
	public InfoModel getInfoRest() {
		return modelUtils.getInfoModel();
	}

	@RequestMapping(value = "/info", method = RequestMethod.GET,
			produces = "application/json;charset=UTF-8")
	@ResponseBody
	public InfoModel getInfo() {
		return modelUtils.getInfoModel();
	}

	//for non-admin angular layout pages
	@RequestMapping(value = "/{layoutType}/layout", method = RequestMethod.GET)
	public String getPage(@PathVariable(value="layoutType") String layoutType) {
		return layoutType + "/layout";
	}

	//for admin angular layout pages
	@RequestMapping(value = "admin/{layoutType}/layout", method = RequestMethod.GET)
	public String getAdminPage(@PathVariable(value="layoutType") String layoutType) {
		return "admin/" + layoutType + "/layout";
	}
}
