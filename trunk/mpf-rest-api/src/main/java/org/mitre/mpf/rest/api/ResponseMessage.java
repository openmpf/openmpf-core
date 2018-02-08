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

package org.mitre.mpf.rest.api;


import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class ResponseMessage extends ResponseEntity<ResponseMessage.Message> {

    public ResponseMessage(String message, HttpStatus httpStatus) {
        super(new Message(message), httpStatus);
    }

    public static ResponseMessage ok(String message) {
        return new ResponseMessage(message, HttpStatus.OK);
    }


    public static class Message {
        private final String _message;

        public Message(String message) {
            _message = message;
        }

        public String getMessage() {
            return _message;
        }
    }
}
