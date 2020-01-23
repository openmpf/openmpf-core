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

import org.mitre.mpf.mvc.model.AuthenticationModel;
import org.mitre.mpf.mvc.util.ModelUtils;
import org.mitre.mpf.rest.api.InfoModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Controller
@Scope("request")
public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    @Autowired
    private ModelUtils modelUtils;


    private static Map<String, Boolean> firstLoginMap = new HashMap<String, Boolean>();

    public static AuthenticationModel getAuthenticationModel(HttpServletRequest request) {
        // get security context from thread local
        SecurityContext context = SecurityContextHolder.getContext();
        boolean authenticated = false;
        boolean admin = false;
        String userPrincipalName = null;

        if(context != null) {
            Authentication authentication = context.getAuthentication();
            if ( authentication != null ) {
                authenticated = true;
                Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
                admin = authorities.contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
            }
        }

        if(request != null && request.getUserPrincipal() != null) {
        	userPrincipalName = request.getUserPrincipal().getName();
        }

        //if doesn't contain, has to be the first login
        boolean userFirstLogin = (firstLoginMap.containsKey(userPrincipalName)) ? firstLoginMap.get(userPrincipalName) : true;
        AuthenticationModel authenticationModel = new AuthenticationModel(authenticated, admin, userPrincipalName, userFirstLogin);
        //set back to false if there is a user
        if(userFirstLogin && userPrincipalName != null) {
        	firstLoginMap.put(userPrincipalName, false);
        }
     	return authenticationModel;
    }

    /** a helper method to put in all the security credentials needed
     *  by the JSP views;
     *  Note:  requires that the ModelAndView passed in is instantiated in
     *      the calling controller; this is also the ModelAndView returned
     *      after the credentials are set
     *  code is a combination of the following:
     *      http://stackoverflow.com/a/10232526/1274852
     *      http://stackoverflow.com/a/12455803/1274852
     */
    public static ModelAndView setSecurityCredentialsForView(
        HttpServletRequest request, // needed for UserPrincipal
        ModelAndView mv )           // the mv of each controller, already instantiated
    {
    	AuthenticationModel authenticationModel = getAuthenticationModel(request);

        mv.addObject("authenticated", authenticationModel.isAuthenticated());
        mv.addObject("isAdmin", authenticationModel.isAdmin());
        mv.addObject("UserPrincipalName", authenticationModel.getUserPrincipalName());

        return mv;
    }

    @RequestMapping(value = { "/login" }, method = RequestMethod.GET)
    public ModelAndView getLogin(
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "bootout", required = false) String bootout,
            @RequestParam(value = "disabled", required = false) String disabled,
            @RequestParam(value = "logout", required = false) String logout,
            @RequestParam(value = "timeout", required = false) String timeout,
            HttpSession session, HttpServletRequest servletRequest) {

        // invalidate session on logout or timeout for extra security
        boolean clearSession = false;

        ModelAndView model = new ModelAndView("login_view");
        if (error != null) {
            log.debug("Invalid username and password");
            model.addObject("error", "Invalid username and password!");
        } else if (bootout != null) {
            log.debug("User booted out");
            model.addObject("error", "You've been logged out because the same user logged in from another location.");
        } else if (disabled != null) {
            log.debug("User account disabled");
            model.addObject("error", "Account is disabled!");
        } else if (logout != null) {
            log.debug("User logged out");
            model.addObject("msg", "You've been logged out successfully.");
            clearSession = true;
        } else if (timeout != null) {
            log.debug("Session timed out");
            model.addObject("msg", "Session timed out or expired.");
            clearSession = true;
        }

        //reset the first login of the user back to true here!
        AuthenticationModel authenticationModel = getAuthenticationModel(servletRequest);
        if(authenticationModel.getUserPrincipalName() != null) {
        	//checking if null because the login?<@RequestParam> urls can be requested without an active login
        	firstLoginMap.put(authenticationModel.getUserPrincipalName(), true);
        }

        if(clearSession) {
            session.invalidate();

            //necessary to prevent a user from recovering a session
            SecurityContextHolder.clearContext();
        }

        // get version info
        InfoModel meta = modelUtils.getInfoModel();
        model.addObject("version", meta.getVersion());
        model.addObject("build", meta.getBuildNum());

        return model;
    }

    @RequestMapping(value = "/user/role-info", method = RequestMethod.GET)
    @ResponseBody
    public AuthenticationModel getSecurityCredentials(HttpServletRequest request /*needed for UserPrincipal*/) {
        return getAuthenticationModel(request);
    }
}
