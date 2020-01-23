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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.HashMap;

@Controller
@Scope("request")
public class TimeoutController {

    private static final Logger log = LoggerFactory.getLogger(TimeoutController.class);

    public static final int CUSTOM_SESSION_TIMEOUT_ERROR_CODE = 901;

    @RequestMapping(value = "/resetSession", method = RequestMethod.GET)
    public void resetTimeout(HttpServletRequest request, HttpServletResponse response, HttpSession session)
            throws IOException {

        log.info("session {} reset session timeout", session);
        HashMap<String,Object> data = new HashMap<String,Object>();
        data.put( "msg", "Session extended");
        AtmosphereController.broadcast(
                AtmosphereChannel.SSPC_SESSION, "OnSessionExtendedByUser", data );
    }


    @RequestMapping(value = "/timeout", method = RequestMethod.GET)
    public ModelAndView timeout(HttpServletRequest request, HttpServletResponse response, HttpSession session)
            throws IOException {

        log.info("session {} timed out", session);

        session.invalidate();

        //necessary to prevent a user from recovering a session
        SecurityContextHolder.clearContext();

        if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            response.sendError(CUSTOM_SESSION_TIMEOUT_ERROR_CODE);
            return null;
        }

        return new ModelAndView("timeout");
    }
}
