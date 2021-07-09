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

import org.mitre.mpf.rest.api.pipelines.Action;
import org.mitre.mpf.rest.api.pipelines.Algorithm;
import org.mitre.mpf.rest.api.pipelines.Pipeline;
import org.mitre.mpf.rest.api.pipelines.Task;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;
import org.mitre.mpf.wfm.data.entities.persistent.Media;

public class JobPart {

    private final BatchJob _job;

    private final Media _media;

    private final int _mediaIndex;

    private final int _taskIndex;

    private final int _actionIndex;


    public JobPart(BatchJob job, Media media, int mediaIndex, int taskIndex, int actionIndex) {
        _job = job;
        _media = media;
        _mediaIndex = mediaIndex;
        _taskIndex = taskIndex;
        _actionIndex = actionIndex;
    }

    public BatchJob getJob() {
        return _job;
    }

    public Media getMedia() {
        return _media;
    }

    public int getMediaIndex() {
        return _mediaIndex;
    }

    public Pipeline getPipeline() {
        return _job.getPipelineElements().getPipeline();
    }

    public Task getTask() {
        return _job.getPipelineElements().getTask(_taskIndex);
    }

    public int getTaskIndex() {
        return _taskIndex;
    }

    public Action getAction() {
        return _job.getPipelineElements().getAction(_taskIndex, _actionIndex);
    }

    public int getActionIndex() {
        return _actionIndex;
    }

    public Algorithm getAlgorithm() {
        return _job.getPipelineElements().getAlgorithm(_taskIndex, _actionIndex);
    }
}
