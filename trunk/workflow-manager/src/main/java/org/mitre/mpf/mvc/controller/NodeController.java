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

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import io.swagger.annotations.*;
import org.h2.util.StringUtils;
import org.mitre.mpf.mvc.model.AuthenticationModel;
import org.mitre.mpf.nms.ServiceDescriptor;
import org.mitre.mpf.rest.api.MpfResponse;
import org.mitre.mpf.rest.api.node.DeployedNodeManagerModel;
import org.mitre.mpf.rest.api.node.DeployedServiceModel;
import org.mitre.mpf.rest.api.node.NodeManagerModel;
import org.mitre.mpf.rest.api.node.ServiceModel;
import org.mitre.mpf.wfm.enums.EnvVar;
import org.mitre.mpf.wfm.nodeManager.NodeManagerStatus;
import org.mitre.mpf.wfm.service.NodeManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

// swagger includes

@Api(value = "Nodes", description = "Node management")
@Controller
@Scope("singleton")
public class NodeController {

	private static final Logger log = LoggerFactory.getLogger(NodeController.class);

	@Autowired
	private NodeManagerService nodeManagerService;

	// TODO: should retrieve info for this using mpfService
	@Autowired
	private NodeManagerStatus nodeManagerStatus;

	private Map<String, ServiceModel> nodeManagerPaletteMap = null;

	NodeController() {
		nodeManagerPaletteMap = new TreeMap<String, ServiceModel>();
	}

	/*
	 * REST API METHODS
	 */

	/*
	 * GET /nodes/info
	 */
	// EXTERNAL
	@RequestMapping(value = "/rest/nodes/info", method = RequestMethod.GET)
	@ApiOperation(value = "Retrieves information about the currently configured node managers and "
			+ "the statuses of the services within each configured node manager.", produces = "application/json", response = DeployedNodeManagerModel.class)
	@ApiResponses(@ApiResponse(code = 200, message = "Successful response"))
	@ResponseBody
	public DeployedNodeManagerModel getNodeManagerInfoRest() {
		return getNodeManagerInfo();
	}

	// INTERNAL
	@RequestMapping(value = "/nodes/info", method = RequestMethod.GET)
	@ResponseBody
	public DeployedNodeManagerModel getNodeManagerInfoSession() {
		return getNodeManagerInfo();
	}

	/*
	 * GET /nodes/hosts
	 */
	// EXTERNAL
	@RequestMapping(value = "/rest/nodes/hosts", method = RequestMethod.GET)
	@ApiOperation(value = "Returns a collection of key-value pairs <String, Boolean> containing a hostname and its node configuration status. "
			+ "If the configuration status is 'true' that hostname is configured to run a node manager.", notes = "The response is a set of JSON key-value pairs, where the key is the hostname and the value is the configuration status.", produces = "application/json")
	@ApiResponses(@ApiResponse(code = 200, message = "Successful response"))
	@ResponseBody
	public Map<String, Boolean> getNodeManagerHostsRest() {
		return getNodeManagerHosts();
	}

	// INTERNAL
	@RequestMapping(value = "/nodes/hosts", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Boolean> getNodeManagerHostsSession() {
		return getNodeManagerHosts();
	}

	/*
	 * GET /nodes/all
	 */
	// EXTERNAL: Only used externally by "mpf list-nodes"
	@RequestMapping(value = "/rest/nodes/all", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity getAllNodesRest(
			@RequestParam(value = "type", required = false, defaultValue="all") String type) {
		return getAllNodes(type);
	}

	// INTERNAL
	@RequestMapping(value = "/nodes/all", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity getAllNodes(
			@RequestParam(value = "type", required = false, defaultValue="all") String type) {

		Set<String> coreNodes = nodeManagerService.getCoreNodes();

		if (type.equals("core")) {
			return ResponseEntity.ok().body(coreNodes);
		}

		Set<String> nodes = new HashSet<>(nodeManagerService.getAvailableNodes());
		if (type.equals("spare")) {
			nodes.removeAll(coreNodes);
			return ResponseEntity.ok().body(nodes);
		}
		if (type.equals("all")) {
			nodes.addAll(coreNodes);
			return ResponseEntity.ok().body(nodes);
		}

		String error = "Unexpected \"type\" value: \"" + type + "\".";
		log.error("Error processing [GET] /nodes/all: " + error);
		return ResponseEntity.badRequest().body(error);
	}

	/*
	 * GET /nodes/master-node
	 * get master node as defined by either the env variable THIS_MPF_NODE
	 * 	or the current host if THIS_MPF_NODE is not defined
	 */
	// INTERNAL
	@RequestMapping(value = "/nodes/master-node", method = RequestMethod.GET)
	@ResponseBody
	public String getMasterNode() {
		String masterNode;
		masterNode = System.getenv(EnvVar.THIS_MPF_NODE);
		if ( StringUtils.isNullOrEmpty( masterNode ) ) {
			// apparently, getting the current host is non-trivial, the solution below is
			//	based on the following articles:
			//		http://stackoverflow.com/questions/7348711/recommended-way-to-get-hostname-in-java
			//		http://stackoverflow.com/a/17958246
			//		http://flyingjxswithjava.blogspot.com/2014/04/execute-external-shell-command-in-java.html
			try {
				InetAddress localhost = InetAddress.getLocalHost();
				masterNode = localhost.getHostName();
			} catch (UnknownHostException e) {
				masterNode = System.getenv(EnvVar.HOSTNAME);
				if ( masterNode == null ) {
					Process p;
					try {
						p = Runtime.getRuntime().exec("hostname");
						p.waitFor();
						BufferedReader reader = new BufferedReader(
								new InputStreamReader(
										p.getInputStream()));
						StringBuilder result = new StringBuilder();
						String line;
						while ((line = reader.readLine()) != null) {
							result.append(line);
						}
						masterNode = result.toString();
						p.destroy();
					} catch (Exception ex) {
						log.error("Unable to find current hostname, returning 'UNKNOWN_HOSTNAME'");
						masterNode = "UNKNOWN_HOSTNAME";
					}
				}
			}
		}
		//feels silly, but this is the easiest way to make a JSON string of the response
		return "{\"master-node\": \"" + masterNode + "\"}";
	}

	/*
	 * GET /nodes/config
	 */
	// EXTERNAL
	@RequestMapping(value = "/rest/nodes/config", method = RequestMethod.GET)
	@ApiOperation(value = "Retrieves the configuration information of all NodeManagers, including all services configured to run on each NodeManager", produces = "application/json", response = NodeManagerModel.class, responseContainer = "List")
	@ApiResponses(@ApiResponse(code = 200, message = "Successful response"))
	@ResponseBody
	public List<NodeManagerModel> getNodeManagerConfigRest() {
		return getNodeManagerConfig();
	}

	// INTERNAL
	@RequestMapping(value = "/nodes/config", method = RequestMethod.GET)
	@ResponseBody
	public List<NodeManagerModel> getNodeManagerConfigSession() {
		return getNodeManagerConfig();
	}

	/*
	 * POST /nodes/config
	 */
	// EXTERNAL POST
	@RequestMapping(value = "/rest/nodes/config", method = RequestMethod.POST)
	@ApiOperation(value = "Save the NodeManager configuration using a JSON array of NodeManagerModel "
			+ "objects.", notes = "Each node manager in the cluster will be notified of the change(s) and attempt to conform to those changes.", produces = "application/json")
	@ApiResponses(@ApiResponse(code = 201, message = "Config saved"))
	public ResponseEntity<MpfResponse> saveNodeManagerConfigRest(
			@ApiParam(required = true, value = "all NodeManagerModel objects as a JSON array specifying all the configurations for all the nodes in the cluster") @RequestBody List<NodeManagerModel> nodeManagerModels,
			HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
					throws InterruptedException, IOException {

		// log.debug("'rest/nodes/config' endpoint entered");

		// POSTing to this external REST endpoint requires the user to be an
		// admin
		MpfResponse mpfResponse = new MpfResponse(
				MpfResponse.RESPONSE_CODE_ERROR,
				"Error while saving the node configuration. Please check server logs.");

		boolean validAuth = false;

		if (httpServletRequest != null) {
			// check the user authentication, only an admin should be able to
			// access this url
			// a highly unlikely security issue could arise if the client side
			// is modified in a way to POST to this url
			AuthenticationModel authenticationModel = LoginController.getAuthenticationModel(httpServletRequest);
			if (authenticationModel.getUserPrincipalName() != null && authenticationModel.isAuthenticated()
					&& authenticationModel.isAdmin()) {
				log.info("Admin user with name '{}' is attempting to save a new node configuration.",
						authenticationModel.getUserPrincipalName());
				// valid auth - try to save the config - the
				// saveNodeConfigSuccess will be set to true in the method below
				// if successful
				validAuth = true;
				saveNodeManagerConfig(nodeManagerModels, mpfResponse);
			} else {
				log.error("Invalid/non-admin user with name '{}' is attempting to save a new node configuration.",
						authenticationModel.getUserPrincipalName());
				mpfResponse.setMessage(MpfResponse.RESPONSE_CODE_ERROR,
						"Node config changes not saved! You do not have the proper user privileges to make this change! Please log in as an admin user.");
				// do not continue! - leads to false return
			}
		} else {
			log.error("Null httpServletRequest - this should not happen.");
			mpfResponse.setMessage(MpfResponse.RESPONSE_CODE_ERROR,
			                       "Unknown error, please check the server logs or try the request again.");
			// do not continue! - leads to false return
		}

		//return 201 for successful post (there still could be an error in the
		//	saveNodeConfigResult) and 401 if bad auth
		return new ResponseEntity<>(mpfResponse, (validAuth) ? HttpStatus.CREATED : HttpStatus.UNAUTHORIZED);
	}

	// INTERNAL POST
	@RequestMapping(value = "/nodes/config", method = RequestMethod.POST)
	@ResponseBody
	@ResponseStatus(value = HttpStatus.CREATED) // return 201 for post
	public MpfResponse saveNodeManagerConfigSession(@RequestBody List<NodeManagerModel> nodeManagerModels)
			throws InterruptedException, IOException {
		MpfResponse mpfResponse = new MpfResponse(
				MpfResponse.RESPONSE_CODE_ERROR,
				"Error while saving the node configuration. Please check server logs.");
		saveNodeManagerConfig(nodeManagerModels, mpfResponse);
		return mpfResponse;
	}

	/*
	 * GET /nodes/services
	 */
	// EXTERNAL
	@RequestMapping(value = "/rest/nodes/services", method = RequestMethod.GET)
	@ApiOperation(value = "Retrieves services that are currently available for deployment to a node.", notes = "In the response, the key is the serviceName and the value is a ServiceModel object.", produces = "application/json", response = ServiceModel.class, responseContainer = "Set")
	@ApiResponses(@ApiResponse(code = 200, message = "Successful response"))
	@ResponseBody
	public Map<String, ServiceModel> getNodeManagerServicePaletteRest()
			throws JsonGenerationException, JsonMappingException, IOException {
		return getNodeManagerServicePalette();
	}

	// INTERNAL
	@RequestMapping(value = "/nodes/services", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, ServiceModel> getNodeManagerServicePaletteSession()
			throws JsonGenerationException, JsonMappingException, IOException {
		return getNodeManagerServicePalette();
	}

	/*
	 * /nodes/services/{serviceName:.+}/start|stop
	 */
	// EXTERNAL START
	@RequestMapping(value = "/rest/nodes/services/{serviceName:.+}/start", method = RequestMethod.POST)
	@ApiOperation(value = "Starts the service named serviceName", notes = "This method returns a NodeServiceStatusChangeResult and a HTTP 200 status code on successful request.",
	produces = "application/json", response = MpfResponse.class)
	@ApiResponses(@ApiResponse(code = 200, message = "Successful response"))
	public ResponseEntity<MpfResponse> startServiceRest(
			@ApiParam(required = true, value = "The fully qualified name of the service to be shutdown, (e.g., localhost.localdomain:markup:1)") @PathVariable("serviceName") String serviceName,
			HttpServletRequest httpServletRequest) {

		// POSTing to this external REST endpoint requires the user to be an
		// admin
		MpfResponse mpfResponse = new MpfResponse(MpfResponse.RESPONSE_CODE_ERROR,
		                                          "Error while starting service. Please check server logs.");

		boolean validAuth = startStopNodeService(httpServletRequest, "start", serviceName,
				mpfResponse);

		//return 200 for successful post (there still could be an error in the
		// 	nodeServiceStatusChangeResult) and 401 if bad auth
		return new ResponseEntity<>(mpfResponse,
				validAuth ? HttpStatus.OK : HttpStatus.UNAUTHORIZED);
	}

	// EXTERNAL STOP
	@RequestMapping(value = "/rest/nodes/services/{serviceName:.+}/stop", method = RequestMethod.POST)
	@ApiOperation(value = "Shuts down the service named serviceName", notes = "This method returns a NodeServiceStatusChangeResult and a HTTP 200 status code on successful request.",
	produces = "application/json", response = MpfResponse.class)
	@ApiResponses(@ApiResponse(code = 200, message = "Successful response"))
	public ResponseEntity<MpfResponse> stopServiceRest(
			@ApiParam(required = true, value = "The fully qualified name of the service to be shutdown, (e.g., localhost.localdomain:markup:1)") @PathVariable("serviceName") String serviceName,
			HttpServletRequest httpServletRequest) {

		// POSTing to this external REST endpoint requires the user to be an
		// admin
		MpfResponse mpfResponse = new MpfResponse(MpfResponse.RESPONSE_CODE_ERROR,
		                                          "Error while stopping service. Please check server logs.");

		boolean validAuth = startStopNodeService(httpServletRequest, "stop", serviceName,
				mpfResponse);

		//return 200 for successful post (there still could be an error in the
		//	nodeServiceStatusChangeResult) and 401 if bad auth
		return new ResponseEntity<>(mpfResponse,
				validAuth ? HttpStatus.OK : HttpStatus.UNAUTHORIZED);
	}

	// INTERNAL START AND STOP
	@RequestMapping(value = "/nodes/services/{serviceName:.+}/{startORstop}", method = RequestMethod.POST)
	@ResponseBody
	@ResponseStatus(value = HttpStatus.OK) // return 200 for successful post in this case
	public MpfResponse startStopServiceSession(@PathVariable("startORstop") String startOrStopStr,
			@PathVariable("serviceName") String serviceName) {

		MpfResponse mpfResponse = new MpfResponse(MpfResponse.RESPONSE_CODE_ERROR,
		                                          "Error while changing service state. Please check server logs.");

		if (startOrStopStr.equals("start")) {
			startService(serviceName, mpfResponse);
		} else if (startOrStopStr.equals("stop")) {
			shutdownService(serviceName, mpfResponse);
		} else {
			String errorStr = "Invalid start or stop path variable of '" + startOrStopStr
					+ "', the first path variable must be 'start' or 'stop'.";
			log.error(errorStr);
			mpfResponse.setMessage(MpfResponse.RESPONSE_CODE_ERROR, errorStr);
		}

		return mpfResponse;
	}

	private DeployedNodeManagerModel getNodeManagerInfo() {
		// This grabs all of the services and does not organize them into a
		// specific host/target
		DeployedNodeManagerModel deployedNodeManagerModel = new DeployedNodeManagerModel();
		for (Map.Entry<String, ServiceDescriptor> entry : nodeManagerStatus.getServiceDescriptorMap().entrySet()) {
			ServiceDescriptor sd = entry.getValue();
			// TODO: can probably merge both *ServiceModel objects in the future
			DeployedServiceModel deployedServiceModel = new DeployedServiceModel(sd);
			deployedNodeManagerModel.getNodeModels().add(deployedServiceModel);
		}
		return deployedNodeManagerModel;
	}

	private Map<String, Boolean> getNodeManagerHosts() {
		// note that this is configured not necessarily operational
		// TODO: need to check what happens when a host is not available
		return nodeManagerStatus.getConfiguredManagerHosts();
		// this map should be updated when the master node is reconfigured (new
		// configuration loaded)
	}

	private List<NodeManagerModel> getNodeManagerConfig() {
		return nodeManagerService.getNodeManagerModels();
	}

	private Map<String, ServiceModel> getNodeManagerServicePalette()
			throws JsonGenerationException, JsonMappingException, IOException {
		nodeManagerPaletteMap = getServiceModels();
		// set the service counts to 1 to be consistent with the hardcoded json
		// file
		for (ServiceModel serviceModel : nodeManagerPaletteMap.values()) {
			serviceModel.setServiceCount(1);
		}
		return nodeManagerPaletteMap;
	}

	private void saveNodeManagerConfig(List<NodeManagerModel> nodeManagerModels,
			final MpfResponse mpfResponse) throws IOException, InterruptedException {

		if (nodeManagerService.saveAndReloadNodeManagerConfig(nodeManagerModels)) {
			log.info("Successfully updated the node config");
			mpfResponse.setResponseCode(MpfResponse.RESPONSE_CODE_SUCCESS);
		} else {
			// should always access the nodeManagerConfigName resource and throw
			// an exception before getting here
			mpfResponse.setMessage(MpfResponse.RESPONSE_CODE_ERROR,
			                       "Failed to access the node manager config resource.");
		}
	}

	/*
	 * This returns a boolean representing valid 'Admin' authentication. The
	 * nodeServiceStatusChangeResult object is also updated with a node service
	 * status change success or failure boolean and an error message
	 */
	private boolean startStopNodeService(HttpServletRequest httpServletRequest, String startOrStopStr,
			String serviceName, MpfResponse mpfResponse) {
		boolean validAuth = false;

		if (httpServletRequest != null && mpfResponse != null) {
			// check the user authentication, only an admin should be able to
			// access this url
			// a highly unlikely security issue could arise if the client side
			// is modified in a way to POST to this url
			AuthenticationModel authenticationModel = LoginController.getAuthenticationModel(httpServletRequest);
			if (authenticationModel.getUserPrincipalName() != null && authenticationModel.isAuthenticated()
					&& authenticationModel.isAdmin()) {
				log.info("Admin user with name '{}' is attempting to start or stop a service with name: '{}'.",
						authenticationModel.getUserPrincipalName(), serviceName);
				validAuth = true;
			} else {
				log.error(
						"Invalid/non-admin user with name '{}' is attempting to start or stop a service with name '{}'.",
						authenticationModel.getUserPrincipalName(), serviceName);
				mpfResponse.setMessage(MpfResponse.RESPONSE_CODE_ERROR,
						"Node service status not changed! You do not have the proper user privileges to make this change! Please log in as an admin user.");
				// do not continue! - validAuth is false
			}
		} else {
			log.error(
					"Null httpServletRequest or nodeServiceStatusChangeResult - this should not happen. The issue must be due to incorrect usage of this method.");
			mpfResponse.setMessage(MpfResponse.RESPONSE_CODE_ERROR, "Unknown error, please check the server logs.");
			// do not continue! - validAuth is false
		}

		// once we know this is an admin user we can continue the process of
		// determining
		// if we would like to start or stop the service
		if (validAuth) {
			if (startOrStopStr.equalsIgnoreCase("start")) {
				startService(serviceName, mpfResponse);
			} else if (startOrStopStr.equalsIgnoreCase("stop")) {
				shutdownService(serviceName, mpfResponse);
			} else {
				String errorStr = "Invalid start or stop option of '" + startOrStopStr
						+ "', the option must be case insensitive 'start' or 'stop'.";
				log.error(errorStr);
				mpfResponse.setMessage(MpfResponse.RESPONSE_CODE_ERROR, errorStr);
			}
		}
		// no need for else

		return validAuth;
	}

	private void startService(String serviceName, final MpfResponse mpfResponse) {
		log.debug("Try to start sevice with name: '{}'" + serviceName);
		boolean result = nodeManagerStatus.startService(serviceName);
		if (result) {
			mpfResponse.setResponseCode(MpfResponse.RESPONSE_CODE_SUCCESS);
		}
		else {
			mpfResponse.setMessage(
					MpfResponse.RESPONSE_CODE_ERROR,
					"Error starting the service with name '" + serviceName + "'. Please check the server logs.");
		}
	}

	private void shutdownService(String serviceName, final MpfResponse mpfResponse) {
		log.debug("Try to shut down sevice with name: '{}'" + serviceName);
		boolean result = nodeManagerStatus.shutdownService(serviceName);
		if (result) {
			mpfResponse.setResponseCode(MpfResponse.RESPONSE_CODE_SUCCESS);
		}
		else {
			mpfResponse.setMessage(
					MpfResponse.RESPONSE_CODE_ERROR,
					"Error shutting down the service with name '" + serviceName + "'. Please check the server logs.");

		}
	}

	private Map<String, ServiceModel> getServiceModels() {
		return nodeManagerService.getServiceModels();
	}

	/*
	 * @RequestMapping(value = "/nodeManager/testExceptionMAV", method =
	 * RequestMethod.GET) public String testExceptionMAV() throws IOException {
	 * throw new IOException(); }
	 *
	 * @RequestMapping(value = "/nodeManager/testExceptionJSON", method =
	 * RequestMethod.GET)
	 *
	 * @ResponseBody public Object testExceptionJSON() throws IOException {
	 * super.setJsonResponse(true); throw new IOException(); }
	 */
}
