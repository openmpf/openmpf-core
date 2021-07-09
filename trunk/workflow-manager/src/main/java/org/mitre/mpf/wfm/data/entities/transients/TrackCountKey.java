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

import java.util.Objects;

public class TrackCountKey {
    private final long _mediaId;

    private final int _taskIdx;

    private final int _actionIdx;

    public TrackCountKey(long mediaId, int taskIdx, int actionIdx) {
        _mediaId = mediaId;
        _taskIdx = taskIdx;
        _actionIdx = actionIdx;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        else if (o instanceof TrackCountKey) {
            var that = (TrackCountKey) o;
            return _mediaId == that._mediaId && _taskIdx == that._taskIdx
                    && _actionIdx == that._actionIdx;
        }
        else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(_mediaId, _taskIdx, _actionIdx);
    }
}
