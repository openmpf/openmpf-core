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

import org.mitre.mpf.mvc.model.AtmosphereChannel;
import org.mitre.mpf.mvc.model.PropertyModel;
import org.mitre.mpf.wfm.service.SystemMessageService;
import org.mitre.mpf.wfm.util.MpfPropertiesConfigurationBuilder;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

 // NOTE: Don't use @Scope("request") because this class should be treated as a singleton.
@Controller
@Scope("singleton")
public class AdminPropertySettingsController
{
	private static final Logger log = LoggerFactory.getLogger(AdminPropertySettingsController.class);

	@Autowired
	private PropertiesUtil propertiesUtil;

	@Autowired
	private SystemMessageService systemMessageService;

    /**
     * Check to see if a change to any of the immutable system properties requires a restart to apply the change..
     * @return Returns true if any immutable system properties have changed and a restart is required, false otherwise
     */
    private boolean checkForRestartRequired() {
        // Get an updated list of property models. Each element contains current value. Check the flags to see if WFM restart is required to apply any change.
        return propertiesUtil.getCustomProperties().stream().anyMatch(PropertyModel::getNeedsRestart);
    }

    /**
     * Gets a list of system properties. If optional parameter propertySet is not specified or is set to 'all', then all system properties are returned.
     * If propertySet is 'mutable', then only the system properties that may be changed without OpenMPF restart are returned.
     * If propertySet is 'immutable', then only the system properties that require restart of OpenMPF to apply property changes are returned.
     * @param propertySet property set selector
     * @return updated property models
     * @throws IOException
     */
    @ResponseBody
    @RequestMapping(value = "/properties", method = RequestMethod.GET)
    public ResponseEntity getProperties(
            @RequestParam(value = "propertySet", required = false, defaultValue="all") String propertySet) {

        if ( propertySet.equalsIgnoreCase("immutable") ) {
            // Get an updated list of property models containing only the immutable properties. Each element contains current value.
            return ResponseEntity.ok().body(propertiesUtil.getImmutableCustomProperties());

        } else if ( propertySet.equalsIgnoreCase("mutable") ) {
            // Get an updated list of property models containing only the mutable properties. Each element contains current value.
            return ResponseEntity.ok().body(propertiesUtil.getMutableCustomProperties());

        } else if ( propertySet.equalsIgnoreCase("all") ) {
            // by default, return all of the system properties - updated to contain current value.
            return ResponseEntity.ok().body(propertiesUtil.getCustomProperties());
        }

        String error = "Unexpected \"propertySet\" value: \"" + propertySet + "\".";
        log.error("Error processing [GET] /properties: " + error);
        return ResponseEntity.badRequest().body(error);
    }

    @ResponseBody
	@RequestMapping(value = "/properties", method = RequestMethod.PUT)
    /** Call this method to save system properties that have changed to the custom mpf properties file.
     * If any detection system properties have changed, that are identified as changeable without OpenMPF restart,
     * then update those detection system properties via PropertiesUtil.
     * Add system message if a restart of OpenMPF is required for any other system property that is changed and
     * requires a restart to apply the change.
     * @param propertyModels list of system properties that have changed since OpenMPF startup.
     * @return updated property models are returned since the needsRestart property may have changed.
     */
	public List<PropertyModel> saveProperties(@RequestBody List<PropertyModel> propertyModels, HttpServletRequest request) {

		if (!LoginController.getAuthenticationModel(request).isAdmin()) {
			throw new IllegalStateException("A non-admin tried to modify properties.");
		}

		if (propertyModels.isEmpty()) {
			return propertyModels;
		}

        // Call method to iterate through the system properties and update any properties that may have been updated on the UI,
		propertiesUtil.setAndSaveCustomProperties(propertyModels);

		// Add system message if a restart of OpenMPF is required.
        if ( checkForRestartRequired() ) {
            systemMessageService.addStandardSystemMessage("eServerPropertiesChanged");
        } else {
            systemMessageService.deleteStandardSystemMessage("eServerPropertiesChanged");
        }

        AtmosphereController.broadcast(AtmosphereChannel.SSPC_PROPERTIES_CHANGED);

        // Get an updated list of property models. Adjust the returned list of PropertyModels so they will indicate
        // whether or not a WFM restart is required to apply a change.
        Set<String> savedProperties = propertyModels.stream()
                .map(PropertyModel::getKey)
                .collect(toSet());

        // Note that the returned list may contain PropertyModels that may have a updated value of needsRestart.
        return propertiesUtil.getCustomProperties().stream()
                .filter(pm -> savedProperties.contains(pm.getKey()))
                .collect(toList());
	}

	//gets the current default job priority value
	@RequestMapping(value = "/properties/job-priority", method = RequestMethod.GET)
	@ResponseBody
	public int getDefaultJobPriority() {
		return propertiesUtil.getJmsPriority();
	}


	@RequestMapping(value = "/properties/{propertyName:.+}", method = RequestMethod.GET)
	public ResponseEntity<?> getProperty(@PathVariable String propertyName) {
        String propertyValue = propertiesUtil.lookup(propertyName);
        if (propertyValue == null) {
            return ResponseEntity.notFound().build();
        }
        boolean isMutable = MpfPropertiesConfigurationBuilder.isMutableProperty(propertyName);
        return ResponseEntity.ok(new PropertyModel(propertyName, propertyValue, !isMutable));
    }
}
