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

package org.mitre.mpf.wfm.util;

import com.google.common.collect.ImmutableList;
import org.apache.commons.configuration2.*;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.mitre.mpf.mvc.model.PropertyModel;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.util.*;

@Component
public class MpfPropertiesConfigurationBuilder {

    private static final Collection<String> MUTABLE_PREFIXES = ImmutableList.of(
            "detection.",
            "http.callback",
            "http.object.storage.",
            "mpf.output.objects.",
            "node.auto.",
            "remote.media.download.",
            "web.broadcast.job.status.enabled",
            "web.job.polling.interval",
            "http.callback.timeout.ms",
            "warn.",
            "markup.",
            "ties.db.url"
    );

    private static final Collection<String> SNAPSHOT_PREFIXES = ImmutableList.of(
            "detection.",
            "markup.",
            "http.object.storage.nginx.service.uri",
            "mpf.output.objects.artifacts.and.exemplars.only",
            "mpf.output.objects.last.task.only");

    @javax.annotation.Resource(name="customPropFile")
    private FileSystemResource customPropFile;
    public void setCustomPropFile(FileSystemResource customPropFile) {
        this.customPropFile = customPropFile;
    }

    @javax.annotation.Resource(name="propFiles")
    private List<Resource> propFiles;
    public void setPropFiles(List<Resource> propFiles) {
        this.propFiles = propFiles;
    }

    private CompositeConfiguration mpfCompositeConfig;

    // return a snapshot to users of this class to prevent the case where a property is being set / updated at the same
    // time that properties are being read out of the config
    // this is volatile to ensure the most updated version is used across threads
    private volatile PropertiesConfiguration mpfConfigSnapshot;

    private PropertiesConfiguration mpfCustomPropertiesConfig;

    public ImmutableConfiguration getCompleteConfiguration() {
        if (mpfConfigSnapshot == null) {
            mpfCompositeConfig = createCompositeConfiguration();
            updateConfigurationSnapshot();
        }
        return mpfConfigSnapshot;
    }

    public synchronized ImmutableConfiguration setAndSaveCustomProperties(List<PropertyModel> propertyModels) {

        // create a new builder and configuration to write the properties to disk
        // without affecting the values of the composite config
        FileBasedConfigurationBuilder<PropertiesConfiguration> tmpMpfCustomPropertiesConfigBuilder =
                createFileBasedConfigurationBuilder(customPropFile);

        Configuration tmpMpfCustomPropertiesConfig;
        try {
            tmpMpfCustomPropertiesConfig = tmpMpfCustomPropertiesConfigBuilder.getConfiguration();
        } catch (ConfigurationException e) {
            throw new IllegalStateException("Cannot create configuration from " + customPropFile + ".", e);
        }

        for (PropertyModel propModel : propertyModels) {
            String key = propModel.getKey();
            String value = propModel.getValue();

            // update all properties that will be written to disk
            tmpMpfCustomPropertiesConfig.setProperty(key, value);

            // update only the mutable properties in the composite config used by the WFM
            if (isMutableProperty(key)) {
                mpfCustomPropertiesConfig.setProperty(key, value);
            }
        }

        try {
            tmpMpfCustomPropertiesConfigBuilder.save();
        } catch (ConfigurationException e) {
            throw new IllegalStateException("Cannot save configuration to " + customPropFile + ".", e);
        }

        updateConfigurationSnapshot();

        return mpfConfigSnapshot;
    }

    public synchronized List<PropertyModel> getCustomProperties() {

        // create a new builder and configuration to read the properties on disk
        FileBasedConfigurationBuilder<PropertiesConfiguration> tmpMpfCustomPropertiesConfigBuilder =
                createFileBasedConfigurationBuilder(customPropFile);

        Configuration tmpMpfCustomPropertiesConfig;
        try {
            tmpMpfCustomPropertiesConfig = tmpMpfCustomPropertiesConfigBuilder.getConfiguration();
        } catch (ConfigurationException e) {
            throw new IllegalStateException("Cannot create configuration from " + customPropFile + ".", e);
        }

        // generate a complete list of property models and determine if a WFM restart is needed for each
        List <PropertyModel> propertyModels = new ArrayList<>();
        Iterator<String> propertyKeyIter = mpfCompositeConfig.getKeys();
        while (propertyKeyIter.hasNext()) {
            String key = propertyKeyIter.next();

            // use uninterpolated values
            String currentValue = getRawValue(mpfCompositeConfig, key);

            if (tmpMpfCustomPropertiesConfig.containsKey(key)) {
                String customValue = getRawValue(tmpMpfCustomPropertiesConfig, key);
                boolean needsRestart = !Objects.equals(currentValue, customValue);
                propertyModels.add(new PropertyModel(key, customValue, needsRestart));
            } else {
                propertyModels.add(new PropertyModel(key, currentValue, false));
            }
        }

        return propertyModels;
    }

    private String getRawValue(Configuration config, String key) {
        Object prop = config.getProperty(key);
        String raw = prop.toString();
        if (prop instanceof Collection<?>) {
            return raw.substring(1, raw.length()-1); // remove the beginning "[" and ending "]"
        }
        return raw;
    }

    private CompositeConfiguration createCompositeConfiguration() {

        if (!customPropFile.exists()) {
            try {
                PropertiesUtil.createParentDir(customPropFile);
                customPropFile.getFile().createNewFile();
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create " + customPropFile + ".", e);
            }
        }

        CompositeConfiguration compositeConfig = new CompositeConfiguration();

        // add resources in the order they are specified in the application context XML;
        // the first configs that are added to the composite override property values in configs that are added later
        for (Resource resource : propFiles) {
            try {
                if (resource.equals(customPropFile)) {
                    mpfCustomPropertiesConfig = createFileBasedConfigurationBuilder(customPropFile).getConfiguration();
                    compositeConfig.addConfiguration(mpfCustomPropertiesConfig);
                } else if (resource.exists()){
                    compositeConfig.addConfiguration(createFileBasedConfigurationBuilder(resource).getConfiguration());
                }
            } catch (ConfigurationException e) {
                throw new IllegalStateException("Cannot create configuration from " + resource + ".", e);
            }
        }

        if (mpfCustomPropertiesConfig == null) {
            throw new IllegalStateException("List of configuration properties files did not contain the " +
                    "custom configuration property file: " + propFiles);
        }

        return compositeConfig;
    }

    private FileBasedConfigurationBuilder<PropertiesConfiguration> createFileBasedConfigurationBuilder(Resource resource) {

        URL url;
        try {
            url = resource.getURL();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot get URL from " + resource + ".", e);
        }

        FileBasedConfigurationBuilder<PropertiesConfiguration> fileBasedConfigBuilder =
                new FileBasedConfigurationBuilder<>(PropertiesConfiguration.class);

        Parameters configBuilderParameters = new Parameters();
        fileBasedConfigBuilder.configure(configBuilderParameters.fileBased().setURL(url)
                .setListDelimiterHandler(new DefaultListDelimiterHandler(',')));

        return fileBasedConfigBuilder;
    }

    private void updateConfigurationSnapshot() {
        PropertiesConfiguration tmpConfig = new PropertiesConfiguration();

        // this will copy over each property one at a time,
        // essentially generating a "flat" config from the composite config
        ConfigurationUtils.copy(mpfCompositeConfig, tmpConfig);

        mpfConfigSnapshot = tmpConfig;
    }

    public static boolean propertyRequiresSnapshot(String key) {
        return SNAPSHOT_PREFIXES.stream().anyMatch(key::startsWith);
    }

    public static boolean isMutableProperty(String key) {
        return MUTABLE_PREFIXES.stream().anyMatch(key::startsWith);
    }
}
