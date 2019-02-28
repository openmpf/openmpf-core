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

package org.mitre.mpf.wfm.service.component;

import org.springframework.stereotype.Service;

import javax.inject.Inject;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import java.util.Set;

@Service
public class ExtrasDescriptorValidatorImpl implements ExtrasDescriptorValidator {

    private final Validator validator;

    @Inject
    public ExtrasDescriptorValidatorImpl(Validator validator) {
        this.validator = validator;
    }

    @Override
    public void validate(JsonExtrasDescriptor descriptor) throws InvalidExtrasDescriptorException {
        Set<ConstraintViolation<JsonExtrasDescriptor>> validationErrors = validator.validate(descriptor);

        if (!validationErrors.isEmpty()) {
            throw new InvalidExtrasDescriptorException(validationErrors);
        }
    }
}
