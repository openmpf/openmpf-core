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

package org.mitre.mpf.wfm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import http.rest.RequestInterceptor;
import http.rest.RestClient;
import http.rest.RestClientException;
import org.apache.http.Header;
import org.apache.http.client.methods.HttpRequestBase;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.junit.*;
import org.junit.runners.MethodSorters;
import org.mitre.mpf.nms.xml.EnvironmentVariable;
import org.mitre.mpf.nms.xml.Service;
import org.mitre.mpf.rest.api.*;
import org.mitre.mpf.rest.api.node.DeployedNodeManagerModel;
import org.mitre.mpf.rest.api.node.DeployedServiceModel;
import org.mitre.mpf.rest.api.node.NodeManagerModel;
import org.mitre.mpf.rest.api.node.ServiceModel;
import org.mitre.mpf.rest.client.CustomRestClient;
import org.mitre.mpf.wfm.enums.ActionType;
import org.mitre.mpf.wfm.ui.Utils;
import org.mitre.mpf.wfm.util.ObjectMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

import static org.junit.Assert.*;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ITComponentLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ITComponentLifecycle.class);
    private static RestClient client;
    private static CustomRestClient customClient;
    private static final ObjectMapper objectMapper = ObjectMapperFactory.customObjectMapper();
    private final int MINUTES = 60 * 1000;  // millisec

    private static String urlBase = "http://localhost:8080/workflow-manager/rest/";

    private ClassLoader classLoader = getClass().getClassLoader();
    private String javaFilePath = classLoader.getResource("JavaTestDetection.json").getPath();
    private String cPlusPlusFilePath = classLoader.getResource("CplusplusHelloWorldComponent.json").getPath();
    private String mediaPath = classLoader.getResource("samples/new_face_video.avi").getPath();
    private boolean javaComponentRegistered = false;
    private boolean javaComponentDeployed = false;
    private boolean cPlusPlusComponentRegistered = false;
    private boolean cPlusPlusComponentDeployed = false;

    @BeforeClass
    public static void init() {
    	setCredentials(false);


        String tomcatBaseUrl = Utils.BASE_URL;
        if (tomcatBaseUrl != null && tomcatBaseUrl.length() > 0) {
            urlBase = tomcatBaseUrl + "/workflow-manager/rest/";
            log.info("TOMCAT_BASE_URL Environment Variable used to set base URL to: {}", urlBase);
        } else {
            log.info("TOMCAT_BASE_URL Environment Variable NOT SET and base URL set to: {}", urlBase);
        }

    }
    
    private static void setCredentials(boolean asAdmin) {
    	RequestInterceptor authorize = new RequestInterceptor() {
            @Override
            public void intercept(HttpRequestBase request) {
            	if(asAdmin) {
            		request.addHeader("Authorization", WebRESTUtils.ADMIN_AUTHORIZATION);
            	} else {
            		request.addHeader("Authorization", WebRESTUtils.MPF_AUTHORIZATION);
            	}                
            }
        };
        client = RestClient.builder().requestInterceptor(authorize).build();
        customClient = (CustomRestClient) CustomRestClient.builder().restClientClass(CustomRestClient.class).requestInterceptor(authorize).build();
    }

    private List<NodeManagerModel> getNodeConfig() {
        String url = urlBase + "nodes/config";
        Map<String, String> params = new HashMap<String, String>();
        List<NodeManagerModel> nodeManagerModels = null;
        try {
            nodeManagerModels = client.get(url, params, new TypeReference<List<NodeManagerModel>>(){});
        } catch (RestClientException e) {
            log.error("RestClientException occurred while getting node manager configuration");
            e.printStackTrace();
        } catch (IOException e) {
            log.error("IOException occurred while getting node manager configuration");
            e.printStackTrace();
        }
        return nodeManagerModels;
    }

    private DeployedNodeManagerModel getNodeManagerInfo() {
        String url = urlBase + "nodes/info";
        Map<String, String> params = new HashMap<String, String>();
        JsonNode node = null;
        DeployedNodeManagerModel model = null;
        try {
            node = client.get(url, params, JsonNode.class);
        } catch (RestClientException e) {
            log.error("RestClientException occurred while getting deployed node manager info");
            e.printStackTrace();
        } catch (IOException e) {
            log.error("IOException occurred while getting deployed node manager info");
            e.printStackTrace();
        }
        try {
            model = objectMapper.treeToValue(node, DeployedNodeManagerModel.class);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return model;
    }

    private void postNodeConfig(List<NodeManagerModel> nodeManagerModels, int expectedResponseCode) {
        String url = urlBase + "nodes/config";
        try {
            Header header = client.create(url, nodeManagerModels, expectedResponseCode);
        } catch (RestClientException e) {
            log.error("RestClientException occurred while posting node manager configuration");
            e.printStackTrace();
        } catch (IOException e) {
            log.error("IOException occurred while posting node manager configuration");
            e.printStackTrace();
        }
    }

    private void registerComponent(String filePath) {
        String url = urlBase + "component/registerViaFile";
        Map<String, String> params = new HashMap<String, String>();
        params.put("filePath", filePath);
        Map<String, String> stringVal = null;
        try {
            stringVal = client.get(url, params, Map.class);
        } catch (RestClientException e) {
            log.error("RestClientException occurred during component registration");
            e.printStackTrace();
        } catch (IOException e) {
            log.error("IOException occurred during component registration");
            e.printStackTrace();
        }
        String message = stringVal.get("message");
        log.info("[registerComponent] message: {}", message);
        assertTrue(message.toLowerCase().contains("successfully"));
    }

    private void unregisterComponent(String filePath) {
        String url = urlBase + "component/unregisterViaFile";
        Map<String, String> params = new HashMap<String, String>();
        params.put("filePath", filePath);
        Map<String, String> stringVal = null;
        try {
//            stringVal = client.get(url, params, Map.class);
            client.get(url, params, Void.class, 204);
        } catch (RestClientException e) {
            log.error("RestClientException occurred while un-registering component");
            e.printStackTrace();
        } catch (IOException e) {
            log.error("IOException occurred while un-registering component");
            e.printStackTrace();
        }
    }

    private void deployComponent(String filePath) {

        // create service
        JSONParser parser = new JSONParser();
        String componentName = "";
        String sourceLanguage = "";
        String batchLibrary = "";
        String serviceName = "";
        String algDesc = "";
        String algName = "";
        ActionType algorithmActionType = ActionType.UNDEFINED;
        List<EnvironmentVariable> componentEnvVars = null;
        try {
            Object obj = parser.parse(new FileReader(filePath));
            JSONObject jsonObject = (JSONObject) obj;
            componentName = (String) jsonObject.get("componentName");
            sourceLanguage = (String) jsonObject.get("sourceLanguage");
            batchLibrary = (String) jsonObject.get("batchLibrary");
            JSONArray envVars = (JSONArray) jsonObject.get("environmentVariables");
            JSONObject algorithm = (JSONObject) jsonObject.get("algorithm");
            componentEnvVars = new ArrayList<EnvironmentVariable>();
            for (int j = 0; j < envVars.size(); j++) {
                JSONObject var = (JSONObject) envVars.get(j);
                String varName = (String) var.get("name");
                String varVal = (String) var.get("value");
                String varSep = (String) var.get("sep");
                EnvironmentVariable newVar = new EnvironmentVariable();
                newVar.setKey(varName);
                newVar.setValue(varVal);
                newVar.setSep(varSep);
                componentEnvVars.add(newVar);
            }
            serviceName = (String) algorithm.get("name");
            algName = (serviceName.replace(' ', '_')).toUpperCase();
            algDesc = (String) algorithm.get("description");
            String algActType = (String) algorithm.get("actionType");
            algorithmActionType = ActionType.parse(algActType);
        } catch (IOException e) {
            log.error("IOException occurred while trying to create new service");
            e.printStackTrace();
        } catch (ParseException e) {
            log.error("ParseException occurred while trying to create new service");
            e.printStackTrace();
        }

        String queueName = "MPF." + algorithmActionType.toString() +  "_" + algName + "_REQUEST";
        Service algorithmService;

        if (sourceLanguage.equalsIgnoreCase("java")) {
            algorithmService = new Service(serviceName, "${MPF_HOME}/bin/start-java-component.sh");
            algorithmService.addArg(batchLibrary);
            algorithmService.addArg(queueName);
            algorithmService.addArg(serviceName);
            algorithmService.setLauncher("generic");
            algorithmService.setWorkingDirectory("${MPF_HOME}/jars");
        } else { // C++
            algorithmService = new Service(serviceName, "${MPF_HOME}/bin/amq_detection_component");
            algorithmService.addArg(batchLibrary);
            algorithmService.addArg(queueName);
            algorithmService.setLauncher("simple");
            algorithmService.setWorkingDirectory("${MPF_HOME}/plugins/" + componentName);
        }
        algorithmService.setDescription(algDesc);
        algorithmService.setEnvVars(componentEnvVars);

        // get current node config
        List<NodeManagerModel> nodeManagerModels = getNodeConfig();
        List<ServiceModel> services = null;
        for (int i = 0; i < nodeManagerModels.size(); i++) {
            log.debug(nodeManagerModels.toString());
            log.debug(nodeManagerModels.get(i).getHost());
            services = nodeManagerModels.get(i).getServices();
            for (ServiceModel service : services) {
                log.debug(service.getServiceName());
            }
        }

        // deploy service to the first host in the list
        ServiceModel newService = new ServiceModel(algorithmService);
        services.add(newService);
        nodeManagerModels.get(0).setServices(services);
        for (int i = 0; i < nodeManagerModels.size(); i++) {
            log.debug(nodeManagerModels.toString());
            log.debug(nodeManagerModels.get(i).getHost());
            services = nodeManagerModels.get(i).getServices();
            for (ServiceModel service : services) {
                log.debug(service.getServiceName());
            }
        }

        //401 - should fail with the current auth
        postNodeConfig(nodeManagerModels, 401);
        //to admin user - init as admin
        setCredentials(true);
        postNodeConfig(nodeManagerModels, 201);
        //back to mpf user
        setCredentials(false);

        // give the service time to start up
        //TODO could replace with loop that checks whether service status is running
        try {
            Thread.sleep(100000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        //verify successful deployment
        String serviceState = "";
        int serviceCount = 0;
        DeployedNodeManagerModel nmModel = getNodeManagerInfo();
        List<DeployedServiceModel> deployedServices = nmModel.getNodeModels();
        String hostname = "localhost.localdomain";
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.error("UnknownHostException occurred while trying to un-deploy service");
            e.printStackTrace();
        }
        String name = hostname + ":" + serviceName + ":1";
        for (DeployedServiceModel deployedService : deployedServices) {
            log.debug("new service name = " + name);
            log.debug("service name = " + deployedService.getName());
            if (deployedService.getName().compareTo(name) == 0) {
                serviceState = deployedService.getLastKnownState();
                serviceCount = deployedService.getServiceCount();
                break;
            }
        }
        log.debug("SERVICE STATE = " + serviceState);
        log.debug("SERVICE COUNT = " + serviceCount);
        assertEquals("Service count does not match.",1,serviceCount);
        assertEquals("Running",serviceState);

    }

    private void undeployComponent(String filePath) {

        // get service name from input file info
        JSONParser parser = new JSONParser();
        String serviceName = "";
        try {
            Object obj = parser.parse(new FileReader(filePath));
            JSONObject jsonObject = (JSONObject) obj;
            JSONObject algorithm = (JSONObject) jsonObject.get("algorithm");
            serviceName = (String) algorithm.get("name");
        } catch (IOException e) {
            log.error("IOException occurred while trying to un-deploy service");
            e.printStackTrace();
        } catch (ParseException e) {
            log.error("ParseException occurred while trying to un-deploy service");
            e.printStackTrace();
        }
        String hostname = "localhost.localdomain";
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.error("UnknownHostException occurred while trying to un-deploy service");
            e.printStackTrace();
        }
        String name = hostname + ":" + serviceName + ":1";

        // shut service down
        String url = urlBase + "nodes/services/" + name + "/stop"; //need fully qualified service name;
        Map<String, String> params = new HashMap<String, String>();        
        try {
        	//need admin credentials to stop a service
        	setCredentials(true);                    	
        	MpfResponse mpfResponse =  
        			customClient.customPostParams(url, params, MpfResponse.class, 200);
        	assertNotNull(mpfResponse);
        	assertEquals(MpfResponse.RESPONSE_CODE_SUCCESS, mpfResponse.getResponseCode());
        	assertTrue(mpfResponse.getMessage() == null);
            setCredentials(false);
        } catch (Exception e) {
            log.error("Exception occurred while trying to shut down service");
            e.printStackTrace();
        }

        // give the service a chance to shut down
        //TODO could replace with loop that checks whether service status is inactive
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // verify that service has shut down
        String serviceState = "";
        int serviceCount = 0;
        DeployedNodeManagerModel nmModel = getNodeManagerInfo();
        List<DeployedServiceModel> deployedServices = nmModel.getNodeModels();
        for (DeployedServiceModel deployedService : deployedServices) {        	
        	log.debug("new service name = " + name);
            log.debug("service name = " + deployedService.getName());
            if (deployedService.getName().compareTo(name) == 0) {
                serviceState = deployedService.getLastKnownState();
                serviceCount = deployedService.getServiceCount();
                break;
            }
        }
        
        log.debug("SERVICE STATE NOW = " + serviceState);
        log.debug("SERVICE COUNT NOW = " + serviceCount);
        assertEquals("InactiveNoStart",serviceState);

        // get the current node config and remove service from it
        List<NodeManagerModel> nodeManagerModels = getNodeConfig();
        int nameCount = 0;
        for (int i = 0; i < nodeManagerModels.size(); i++) {
            log.debug(nodeManagerModels.toString());
            log.debug("hostname = " + hostname);
            log.debug(nodeManagerModels.get(i).getHost());
            if (nodeManagerModels.get(i).getHost().compareTo(hostname) == 0) {
                List<ServiceModel> services = nodeManagerModels.get(i).getServices();
                for (int j = 0; j < services.size(); j++) {
                    log.debug(services.get(j).getServiceName());
                    if (services.get(j).getServiceName().compareTo(serviceName) == 0) {
                        services.remove(j);
                    }
                }
                // check that service has been removed
                for (ServiceModel service : services) {
                    log.debug(service.getServiceName());
                    if (service.getServiceName().compareTo(name) == 0) {
                        ++nameCount;
                    }
                }
            }
        }
        assertEquals(nameCount, 0);

        // post updated node config
        //401 - should fail with the current auth
        postNodeConfig(nodeManagerModels, 401);
        //set as admin user
        setCredentials(true);
        postNodeConfig(nodeManagerModels, 201);
        //back to mpf user
        setCredentials(false);
    }

    private void runJob(String filePath) {

        // determine pipeline name
        String pipelineName = "";
        JSONParser parser = new JSONParser();
        try {
            Object obj = parser.parse(new FileReader(filePath));
            JSONObject jsonObject = (JSONObject) obj;
            JSONObject algorithm = (JSONObject) jsonObject.get("algorithm");
            String serviceName = (String) algorithm.get("name");
            String algActType = (String) algorithm.get("actionType");
            ActionType algorithmActionType = ActionType.parse(algActType);
            String algName = serviceName.toUpperCase();
            log.debug("alg name = " + algName);
            pipelineName = "DEFAULT_" + algName + "_PIPELINE";
            pipelineName = algName + " " + algorithmActionType.toString() + " PIPELINE";
        } catch (FileNotFoundException e) {
            log.error("FileNotFoundException occurred while trying to determine pipeline name");
            e.printStackTrace();
        } catch (ParseException e) {
            log.error("ParseException occurred while trying to determine pipeline name");
            e.printStackTrace();
        } catch (IOException e) {
            log.error("IOException occurred while trying to determine pipeline name");
            e.printStackTrace();
        }

        // make sure that default pipeline for algorithm exists
        // note that there are valid cases where it may not,
        // such as for an algorithm that has required states
        String url = urlBase + "pipelines";
        Map <String, String> params = new HashMap<String, String>();
        List<String> availableWorkPipelines = null;
        try {
            availableWorkPipelines = customClient.get(url, params, new TypeReference<List<String>>() {});
        } catch (RestClientException e) {
            log.error("RestException occurred while trying to verify that pipeline exists");
            e.printStackTrace();
        } catch (IOException e) {
            log.error("IOException occurred while trying to verify that pipeline exists");
            e.printStackTrace();
        }

        if (availableWorkPipelines.contains(pipelineName)) {
            // create job
            JobCreationRequest jobCreationRequest = new JobCreationRequest();
            List<JobCreationMediaData> media = new LinkedList<>();
            media.add(new JobCreationMediaData("file:" + mediaPath));
            jobCreationRequest.setPipelineName(pipelineName);
            jobCreationRequest.setMedia(media);
            JobCreationResponse jobCreationResponse = null;
            try {
                jobCreationResponse = customClient.customPostObject(urlBase + "jobs", jobCreationRequest, JobCreationResponse.class);
            } catch (IOException | RestClientException e) {
                e.printStackTrace();
            }
            // verify successful job creation
            assert(jobCreationResponse.getJobId() != -1);

            // give the job time to finish
            //TODO could replace with loop that checks whether job status is complete
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // verify that job finished
            url = urlBase + "jobs";
            //params.put("v", "")
            url = url + "/" + Long.toString(jobCreationResponse.getJobId());
            SingleJobInfo jobInfo = null;
            try {
                jobInfo = client.get(url, params, SingleJobInfo.class);
            } catch (RestClientException | IOException e) {
                e.printStackTrace();
            }
            
            log.debug("jobsInfo status: " + jobInfo.getJobStatus());
            assertNotNull(jobInfo);
            assertEquals(jobCreationResponse.getJobId(), jobInfo.getJobId().longValue());
            
            assertEquals("COMPLETE", jobInfo.getJobStatus());
        } else {
            log.info("Could not find job pipeline with name " + pipelineName);
        }
    }

    private void registerJavaComponent() {
        javaComponentRegistered = false;
        registerComponent(javaFilePath);
        javaComponentRegistered = true;
    }

    private void unregisterJavaComponent() {
        if (javaComponentRegistered) {
            unregisterComponent(javaFilePath);
            javaComponentRegistered = false;
        }
    }

    private void registerAndDeployJavaComponent() {
        registerJavaComponent();
        javaComponentDeployed = false;
        if (javaComponentRegistered) {
            deployComponent(javaFilePath);
            javaComponentDeployed = true;
        }
    }

    private void undeployAndUnregisterJavaComponent() {
        if (javaComponentDeployed) {
            undeployComponent(javaFilePath);
            javaComponentDeployed = false;
        }
        unregisterJavaComponent();
    }

    private void registerCplusPlusComponent() {
        cPlusPlusComponentRegistered = false;
        registerComponent(cPlusPlusFilePath);
        cPlusPlusComponentRegistered = true;
    }

    private void unregisterCplusPlusComponent() {
        if (cPlusPlusComponentRegistered) {
            unregisterComponent(cPlusPlusFilePath);
            cPlusPlusComponentRegistered = false;
        }
    }

    private void registerAndDeployCplusPlusComponent() {
        registerCplusPlusComponent();
        cPlusPlusComponentDeployed = false;
        if (cPlusPlusComponentRegistered) {
            deployComponent(cPlusPlusFilePath);
            cPlusPlusComponentDeployed = true;
        }
    }

    private void undeployAndUnregisterCplusPlusComponent() {
        if (cPlusPlusComponentDeployed) {
            undeployComponent(cPlusPlusFilePath);
            cPlusPlusComponentDeployed = false;
        }
        unregisterCplusPlusComponent();
    }

    @After
    // should not need to clean up, but just in case...
    public void cleanup() {
        if (javaComponentDeployed) {
            undeployComponent(javaFilePath);
        }
        if (javaComponentRegistered) {
            unregisterComponent(javaFilePath);
        }
        if (cPlusPlusComponentDeployed) {
            undeployComponent(cPlusPlusFilePath);
        }
        if (cPlusPlusComponentRegistered) {
            unregisterComponent(cPlusPlusFilePath);
        }
    }

    /*
    @Test(timeout = 5*MINUTES)
    public void testA_Registration() {
        registerJavaComponent();
        registerCplusPlusComponent();
        unregisterJavaComponent();
        unregisterCplusPlusComponent();
    }

    @Test(timeout = 10*MINUTES)
    public void testB_Deployment() {
        registerAndDeployJavaComponent();
        registerAndDeployCplusPlusComponent();
        undeployAndUnregisterJavaComponent();
        undeployAndUnregisterCplusPlusComponent();
    }
    */

    // This test covers the same functionality as the above tests.
    // TODO: move this basic fuctionality (deploy component and run job) into ITComponentRegistration.
    // Ignoring for now -- do not have resources for java component registration.
    @Ignore
    @Test(timeout = 20*MINUTES)
    public void testC_runJob() {
        registerAndDeployJavaComponent();
        registerAndDeployCplusPlusComponent();
        assertTrue(javaComponentDeployed);
        assertTrue(cPlusPlusComponentDeployed);
        if (javaComponentDeployed) {
            runJob(javaFilePath);
        }
        if (cPlusPlusComponentDeployed) {
            runJob(cPlusPlusFilePath);
        }
        undeployAndUnregisterJavaComponent();
        undeployAndUnregisterCplusPlusComponent();
    }

}
