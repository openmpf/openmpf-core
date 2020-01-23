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

package org.mitre.mpf.mvc;

import org.mitre.mpf.rest.api.MessageModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.builders.ResponseMessageBuilder;
import springfox.documentation.schema.AlternateTypeRules;
import springfox.documentation.schema.ModelRef;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.ResponseMessage;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger.web.UiConfiguration;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

import java.time.Instant;
import java.util.List;

//reference - http://springfox.github.io/springfox/docs/snapshot/
@Configuration
@EnableWebMvc //NOTE: Only needed in a non-springboot application
@EnableSwagger2
//@Profile("website") //this might help with this class breaking the mvn tests
@Profile("!jenkins")
public class SwaggerConfig {

    @Bean
    public Docket api(){
        var globalResponses = getGlobalResponses();

        return new Docket(DocumentationType.SWAGGER_2)
            .select()
            // only show APIs which has the @ApiOperation annotation
            //  alternative is any(), but that defaults to somewhat useless autogen documentation
            .apis(RequestHandlerSelectors.withMethodAnnotation(
                io.swagger.annotations.ApiOperation.class))
            .paths(PathSelectors.ant("/rest/**"))
            .build()
            // list classes to be ignored in parameters (useful for optional internal parameters)
            .ignoredParameterTypes(javax.servlet.http.HttpSession.class)
            // opt out of auto-generated response code and their default message
            .useDefaultResponseMessages(false)
            .alternateTypeRules(AlternateTypeRules.newRule(Instant.class, String.class))
            .globalResponseMessage(RequestMethod.GET, globalResponses)
            .globalResponseMessage(RequestMethod.DELETE, globalResponses)
            .globalResponseMessage(RequestMethod.POST, globalResponses)
            .globalResponseMessage(RequestMethod.PUT, globalResponses)
            .apiInfo(apiInfo());
    }

    private static ApiInfo apiInfo() {
        ApiInfo apiInfo = new ApiInfo(
            "Workflow Manager's REST API",  // title
            "REST-based web services for the Workflow Manager",  // description
            "",  // terms of service url
            "",  // contact email
            "",
            "",  // license name
            ""  // license url
        );
        return apiInfo;
    }

    @Bean
    UiConfiguration uiConfig() {
        return new UiConfiguration( null );  // disable validation of resulting Swagger JSON at http://online.swagger.io/validator
    }


    private static List<ResponseMessage> getGlobalResponses() {
        var unauthorized = new ResponseMessageBuilder()
                .code(HttpStatus.UNAUTHORIZED.value())
                .message("Unauthorized")
                .responseModel(new ModelRef(MessageModel.class.getSimpleName()))
                .build();

        return List.of(unauthorized);
    }
}
