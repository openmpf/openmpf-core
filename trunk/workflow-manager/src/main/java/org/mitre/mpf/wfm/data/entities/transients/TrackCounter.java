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


package org.mitre.mpf.wfm.data.entities.transients;

import java.util.HashMap;
import java.util.Map;

public class TrackCounter {

    private final Map<TrackCountKey, TrackCountEntry> _counts = new HashMap<>();

    public TrackCountEntry get(long mediaId, int taskIdx, int actionIdx) {
        return _counts.get(new TrackCountKey(mediaId, taskIdx, actionIdx));
    }

    public void set(long mediaId, int taskIdx, int actionIdx, String trackType, int count) {
        _counts.put(new TrackCountKey(mediaId, taskIdx, actionIdx),
                    new TrackCountEntry(mediaId, taskIdx, actionIdx, trackType, count));
    }
}
