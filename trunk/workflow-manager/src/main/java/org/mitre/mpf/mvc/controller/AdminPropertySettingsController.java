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

package org.mitre.mpf.mvc.controller;

import static java.util.stream.Collectors.toList;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import javax.servlet.http.HttpServletRequest;
import org.mitre.mpf.mvc.model.PropertyModel;
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.data.Redis;
import org.mitre.mpf.wfm.data.RedisImpl;
import org.mitre.mpf.wfm.service.MpfService;
import org.mitre.mpf.wfm.service.PipelineService;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Scope;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

// swagger includes

 @Api(value = "properties", description = "Properties get and save")

// NOTE: Don't use @Scope("request") because this class should be treated as a singleton.

@Controller
@Scope("singleton")
@Profile("website")
public class AdminPropertySettingsController 
{	
	private static final Logger log = LoggerFactory.getLogger(AdminPropertySettingsController.class);

	@Autowired
	@Qualifier(PropertiesUtil.REF)
	private PropertiesUtil propertiesUtil;

	@Autowired
	private MpfService mpfService;

	@Autowired
	@Qualifier("loadedProperties")
	private Properties currentProperties;

	@Autowired
	private PipelineService pipelineService;

    @Autowired
    @Qualifier(RedisImpl.REF)
    private Redis redis;

    @ApiOperation(value = "Gets a list of system properties. If optional parameter whichPropertySet is not specified or is set to 'all', then all system properties are returned. "
        + "If whichPropertySet is 'mutable', then only the system properties that may be changed without OpenMPF restart are returned. "
        + "If whichPropertySet is 'immutable', then only the system properties that require restart of OpenMPF to apply property changes are returned.",
        produces = "application/json", response=PropertyModel.class, responseContainer="List")
    @ApiResponses(value = {
        @ApiResponse(code = 200, message = "Successful response"),
        @ApiResponse(code = 401, message = "Bad credentials")})
	@ResponseBody
	@RequestMapping(value = "/properties", method = RequestMethod.GET)
	public List<PropertyModel> getProperties(@RequestParam(value = "whichPropertySet", required = false, defaultValue="all") String whichPropertySet) throws IOException {
		Properties customProperties = getCustomProperties();

		// TODO detection.models.dir.path treated as a special case
		if ( whichPropertySet.equalsIgnoreCase("mutable") ) {
            return currentProperties.entrySet()
                .stream()
                .filter( e -> e.getKey().toString().startsWith("detection.") )
                .filter( e -> !e.getKey().toString().equals("detection.models.dir.path") )
                .map(e -> convertEntryToModel(e, customProperties))
                .collect(toList());
        } else if ( whichPropertySet.equalsIgnoreCase("immutable") ) {
            return currentProperties.entrySet()
                .stream()
                .filter( e -> !e.getKey().toString().startsWith("detection.") || e.getKey().toString().equals("detection.models.dir.path") )
                .map(e -> convertEntryToModel(e, customProperties))
                .collect(toList());
        } else {
            return currentProperties.entrySet()
                .stream()
                .map(e -> convertEntryToModel(e, customProperties))
                .collect(toList());
        }
	}


	private static PropertyModel convertEntryToModel(Map.Entry<?, ?> entry, Properties customProperties) {
		String key = entry.getKey().toString();
		String currentValue = Objects.toString(entry.getValue(), null);
		String modelValue = customProperties.getProperty(key, currentValue);

        // TODO, issue with detection.models.dir.path which is initialized using ${mpf.share.path} and already digested by Spring at this point. So, detection.models.dir.path exclusion is required
		// Any property that starts with "detection." may be changed without requiring restart of OpenMPF, with the single exception of the detection.models.dir.path property.
        boolean isValueChanged = !Objects.equals(currentValue, modelValue);
		boolean needsRestartIfChanged = !key.startsWith("detection.") && !key.equals("detection.models.dir.path");
		return new PropertyModel(key, modelValue, isValueChanged, needsRestartIfChanged);
	}


	@ResponseBody
	@RequestMapping(value = "/properties", method = RequestMethod.PUT)
    /** Call this method to save system properties that have changed to the custom mpf properties file.
     * If any detection system properties have changed, that are identified as changeable without OpenMPF restart,
     * then update those detection system properties via PropertiesUtil. The updated detection system properties will also
     * be stored in REDIS, so updated values can be used in the construction of new pipelines (created using newly created tasks).
     * Add system message if a restart of OpenMPF is required for any other system property that is changed and
     * requires a restart to apply the change.
     * @param propertyModels list of system properties that have changed since OpenMPF startup.
     */
	public void saveProperties(@RequestBody List<PropertyModel> propertyModels, HttpServletRequest request) throws WfmProcessingException, IOException {
		if (!LoginController.getAuthenticationModel(request).isAdmin()) {
			throw new IllegalStateException("A non-admin tried to modify properties.");

		}

		if (propertyModels.isEmpty()) {
			return;
		}

		Properties customProperties = getCustomProperties();

		for (PropertyModel pm : propertyModels) {
            log.info("AdminPropertySettingsController.saveProperties: debug, processing pm = " + pm);
		    // Not all of the property changes require a restart of OpenMPF, set needsRestart based upon whether or not a restart is required if a properties value has changed.
            pm.setIsValueChanged(pm.getValue());
            log.info("AdminPropertySettingsController.saveProperties: debug, updated pm.getIsValueChanged()= " + pm.getIsValueChanged());
            pm.setNeedsRestart(pm.getIsValueChanged() && pm.getNeedsRestartIfChanged());
            log.info("AdminPropertySettingsController.saveProperties: debug, updated pm.getNeedsRestart() = "+ pm.getNeedsRestart());
            log.info("AdminPropertySettingsController.saveProperties: debug, after update pm = " + pm);
			customProperties.setProperty(pm.getKey(), pm.getValue());
		}

		try (OutputStream outputStream = propertiesUtil.getCustomPropertiesFile().getOutputStream()) {
			customProperties.store(outputStream, "modified");
		}

		// Call method to iterate through the system properties that may have been updated on the UI,
        // and store any of the "detection." system property values that were changed.
        // Note that PropertiesUtils methods updateDetectionSystemPropertyValues and createTransientDetectionSystemProperties are synchronized
        // so that a batch jobs detection system properties will stay constant while a job is running.
        propertiesUtil.updateDetectionSystemPropertyValues(propertyModels);

		// After any dynamic detection system properties have been updated, then refresh the algorithm default values so
        // they will be applied to new pipelines
        pipelineService.refreshAlgorithmDefaultValues();

		// Add system message if a restart of OpenMPF is required.
        if ( propertyModels.stream().anyMatch(pm -> pm.getNeedsRestart() ) ) {
            log.info("AdminPropertySettingsController.saveProperties: debug, a property that requires a restart was found");
            mpfService.addStandardSystemMessage("eServerPropertiesChanged");
        }
	}


	private Properties getCustomProperties() throws IOException {
		return PropertiesLoaderUtils.loadProperties(propertiesUtil.getCustomPropertiesFile());
	}


	//gets the current default job priority value
	@RequestMapping(value = "/properties/job-priority", method = RequestMethod.GET)
	@ResponseBody
	public int getDefaultJobPriority() {
		return propertiesUtil.getJmsPriority();
	}	
}