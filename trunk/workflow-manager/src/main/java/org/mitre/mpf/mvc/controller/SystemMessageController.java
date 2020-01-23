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


import io.swagger.annotations.*;
import org.mitre.mpf.mvc.model.AuthenticationModel;
import org.mitre.mpf.rest.api.MpfResponse;
import org.mitre.mpf.wfm.data.entities.persistent.SystemMessage;
import org.mitre.mpf.wfm.service.SystemMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.QueryParam;
import java.io.IOException;
import java.util.List;

/**
 *  This controller enables Workflow manager to manage system level messages, such as notifying users that
 *  a server restart is needed. The messages are stored in the SQL database, but all interactions with the
 *  database should be done via methods in this class.
 */
@Api( value = "System Message",
        description = "Send and retrieve system messages for clients" )
@Controller
public class SystemMessageController {

    private static final Logger log = LoggerFactory.getLogger(SystemMessageController.class);

    @Autowired
    private SystemMessageService systemMessageService;

    /* *************** REST endpoints and implementations **************** */
    /* used http://websystique.com/springmvc/spring-mvc-4-restful-web-services-crud-example-resttemplate/ as CRUD example */

    @RequestMapping(value = {"/system-message"}, method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE )
    @ResponseBody
    public List<SystemMessage> getAllSystemMessages() {
        return systemMessageService.getSystemMessagesByType( "all" );
    }

    @RequestMapping(value = {"/rest/system-message"}, method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE )
    @ApiOperation(value="Retrieves all system messages.",
            notes="Returns a JSON array of all System Messages.",
            produces = MediaType.APPLICATION_JSON_VALUE, response=SystemMessage.class, responseContainer="List" )
    @ApiResponses(@ApiResponse(code = 200, message = "Successful response"))
    @ResponseBody
    public List<SystemMessage> getAllSystemMessagesRest() { return getAllSystemMessages(); }

    @RequestMapping(value = {"/rest/system-message/type/{typeFilter}"}, method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE )
    @ApiOperation(value="Retrieves all system messages of the given type.",
            notes="Returns a JSON array of System Messages matching typeFilter.",
            produces = MediaType.APPLICATION_JSON_VALUE, response=SystemMessage.class, responseContainer="List" )
    @ApiResponses(@ApiResponse(code = 200, message = "Successful response"))
    @ResponseBody
    public List<SystemMessage>  getFilteredSystemMessagesRest(
            @ApiParam(value = "The message type to find", allowableValues = "all,admin,login", required = true) @PathVariable("typeFilter") String typeFilter ) {
        return systemMessageService.getSystemMessagesByType( typeFilter );
    }


    private ResponseEntity<MpfResponse> changeSystemMessageQueue(String op,
                                                                 SystemMessage systemMessage,
                                                                 long id,
                                                                 HttpServletRequest httpServletRequest,
                                                                 HttpServletResponse httpServletResponse)
    {
        boolean validAuth = false;
        HttpStatus httpStatus;
        MpfResponse mpfResponse;
        if (httpServletRequest != null) {
            AuthenticationModel authenticationModel = LoginController.getAuthenticationModel(httpServletRequest);
            if ( authenticationModel.canProceedAsAdmin() ) {
                log.info("Admin user with name '{}' is attempting to {} a system message.", authenticationModel.getUserPrincipalName(), op);
                String logmsg = null;
                validAuth = true;
                if ( op.equals( "POST" ) && systemMessage != null ) {
                    systemMessageService.addSystemMessage(systemMessage);
                    logmsg = "Successfully added the system message.";
                    mpfResponse = new MpfResponse(MpfResponse.RESPONSE_CODE_SUCCESS, logmsg);
                    httpStatus = HttpStatus.CREATED;
                }
                else if ( op.equals( "PUT" ) && systemMessage != null ) {
                    systemMessageService.addSystemMessage(systemMessage);
                    logmsg = "Successfully added the system message.";
                    mpfResponse = new MpfResponse(MpfResponse.RESPONSE_CODE_SUCCESS, logmsg);
                    httpStatus = HttpStatus.CREATED;
                }
//                else if ( op.equals( "PUT_standard" ) && msgID != null ) {
//                    systemMessage = mpfService.addStandardSystemMessage( msgID );
//                    logmsg = "Successfully added the system message.";
//                    mpfResponse = new MpfResponse(0, logmsg);
//                    httpStatus = HttpStatus.CREATED;
//                }
                else if ( op.equals( "DELETE" ) && id > 0 ) {
                    systemMessage = systemMessageService.deleteSystemMessage( id );
                    logmsg = "Successfully deleted the system message.";
                    mpfResponse = new MpfResponse(MpfResponse.RESPONSE_CODE_SUCCESS, logmsg);
                    httpStatus = HttpStatus.OK;
                }
                else {
                    // should never get there, since this is a private method
                    logmsg = "Bad request.";
                    mpfResponse = new MpfResponse(MpfResponse.RESPONSE_CODE_ERROR, logmsg);
                    httpStatus = HttpStatus.BAD_REQUEST;
                }
                log.info( logmsg + "  The message " + ( (systemMessage==null) ? "did not exist in the database." : "was:  " + systemMessage.getMsg() ) );
            }
            else {
                mpfResponse = new MpfResponse(MpfResponse.RESPONSE_CODE_ERROR,
                                              "Only admins can add system messages.");
                httpStatus = HttpStatus.UNAUTHORIZED;
                log.error("Invalid/non-admin user with name '{}' is attempting to save a new system message.", authenticationModel.getUserPrincipalName());
                // do not continue! - leads to false return
            }
        }
        else {
            // should never get here
            mpfResponse = new MpfResponse(MpfResponse.RESPONSE_CODE_ERROR,
                                          "Error while processing system message.");
            httpStatus = HttpStatus.BAD_REQUEST;
            log.error("Null httpServletRequest - this should not happen.");
            // do not continue! - leads to false return
        }

        ResponseEntity<MpfResponse> responseEntity = new ResponseEntity<>(mpfResponse, httpStatus );
        return responseEntity;
    }

    @RequestMapping(value = {"/rest/system-message"}, method = RequestMethod.POST )
    @ApiOperation(value="Adds a system message.",
            produces = "application/json", response = MpfResponse.class )
    @ApiResponses(@ApiResponse(code = 201, message = "Successfully added"))
    @ApiImplicitParams({    // need to use this instead of ApiParam because it causes the rendering of the Swagger page to be wrong for the type of parameter
            @ApiImplicitParam(name = "msg", value = "The message", required = true, dataType = "string", paramType = "body"),
            @ApiImplicitParam(name = "msgType", value = "The message type refers to the audience it is intended for (defaults to 'all')", allowableValues = "all,admin,login", required = false, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "removeStrategy", value = "Strategy governing how a message is to be removed from the queue (defaults to 'manual'", allowableValues = "atServerStartup,manual", required = false, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "severity", value = "Severity of the message (defaults to 'info')", allowableValues = "info,warning,error", required = false, dataType = "string", paramType = "query")
    })
    @ResponseBody
    public ResponseEntity<MpfResponse> postSystemMessageRest(
            @RequestBody String msg,
            @QueryParam("msgType") String msgType,
            @QueryParam ("removeStrategy") String removeStrategy,
            @QueryParam("severity") String severity,
            HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse )
            throws InterruptedException, IOException
    {
        SystemMessage systemMessage = new SystemMessage();
        systemMessage.setMsg( msg );
        systemMessage.setMsgType( msgType );
        systemMessage.setRemoveStrategy( removeStrategy );
        systemMessage.setSeverity( severity );
        //P038: todo: would prefer to return the ID of the newly POSTed item within or instead of MpfResponse since now we have to do a GET
        //      of all the messages just to find out which ID we can delete later
        return changeSystemMessageQueue( "POST", systemMessage, -1, httpServletRequest, httpServletResponse );
    }


//todo: P038: this was working before changing the msgID to autogenerate, but needs further testing to verify it still works for all use cases
//    @RequestMapping(value = {"/rest/system-message"}, method = RequestMethod.PUT )
//    @ApiOperation(value="Adds a system message or updates an existing one.",
//            notes="The only parameter in the JSON body you need to fill in is the message; you can leave everything else out and they will default to default values. "
//                    + "The optional datePosted property MUST be of the form \"yyyy-MM-dd'T'HH:mm:ss.SSSX\"; if you leave this property out (highly recommended), it will correctly timestamp the message.  "
//                    + "The optional msgType property MUST be one of \"all\", \"admin\", or \"login\".  "
//                    + "The optional removeStrategy property MUST be either \"atServerStartup\" or \"manual\".  "
//                    + "This method returns a MpfResponse and a HTTP 200 status code on successful request.",
//            produces = "application/json", response = MpfResponse.class )
//    @ApiResponses(value = {
//            @ApiResponse(code = 201, message = "Successfully added"),
//            @ApiResponse(code = 400, message = "Malformed JSON input, especially incorrect date format"),
//            @ApiResponse(code = 401, message = "Bad credentials") })
//    @ResponseBody
//    public ResponseEntity<MpfResponse> addSystemMessageRest(
//            @ApiParam(value = "The message in JSON format", required = true) @RequestBody SystemMessage systemMessage,
//            HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse )
//            throws InterruptedException, IOException
//    {
//        return changeSystemMessageQueue( "PUT", systemMessage, null, httpServletRequest, httpServletResponse );
//    }

//todo: P038: this was working before changing the msgID to autogenerate, but should be changed to a POST with allowable values
//    @RequestMapping(value = {"/rest/system-message/{msgID}"}, method = RequestMethod.PUT )
//    @ApiOperation(value="Adds a standard system message.",
//            notes="Adds a standard system message.  The only one currently defined is 'eServerPropertiesChanged'.  The only response is the HTTP response code.")
//    @ApiResponses(value = {
//            @ApiResponse(code = 201, message = "Successfully added"),
//            @ApiResponse(code = 401, message = "Bad credentials") })
//    @ResponseBody
//    public ResponseEntity<MpfResponse> addSystemMessageRest(
//            @ApiParam(value = "message ID (currently, only 'eServerPropertiesChanged' is supported", required = true) @PathVariable("msgID") String msgID,
//            HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse )
//            throws InterruptedException, IOException
//    {
//        return changeSystemMessageQueue( "PUT_standard", null, msgID, httpServletRequest, httpServletResponse );
//    }

    @RequestMapping(value = {"/rest/system-message/{id}"}, method = RequestMethod.DELETE )
    @ApiOperation(value="Deletes a system message identified by id.",
            notes="By design, if you delete a non-existent id, the response code is still 200 since the server no longer has (and may never have had) a message with that id.",
            produces = "application/json", response = MpfResponse.class )
    @ApiResponses(@ApiResponse(code = 200, message = "Successfully deleted"))
    @ResponseBody
    public ResponseEntity<MpfResponse> deleteSystemMessageRest( @PathVariable("id") long id,
                               HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse )
            throws InterruptedException, IOException
    {
        return changeSystemMessageQueue( "DELETE", null, id, httpServletRequest, httpServletResponse );
    }

    /* *************** init and cleanup **************** */
    /* purging of system messages is done in WfmStartup.java, due to @Autowired and static incompatibilities */
}
