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

import com.google.common.collect.AbstractSequentialIterator;
import com.google.common.collect.Streams;
import org.mitre.mpf.wfm.data.entities.persistent.BatchJob;
import org.mitre.mpf.wfm.data.entities.persistent.Media;

import java.util.stream.Stream;

public class JobPartsIter {

    private JobPartsIter(){
    }

    public static Iterable<JobPart> of(BatchJob job) {
        return () -> stream(job).iterator();
    }

    public static Stream<JobPart> stream(BatchJob job) {
        return job.getMedia().stream().flatMap(m -> stream(job, m));
    }


    public static Iterable<JobPart> of(BatchJob job, Media media) {
        return () -> new JobPartsFixedMediaIter(job, media);
    }

    public static Stream<JobPart> stream(BatchJob job, Media media) {
        return Streams.stream(new JobPartsFixedMediaIter(job, media));
    }


    private static class JobPartsFixedMediaIter extends AbstractSequentialIterator<JobPart> {

        public JobPartsFixedMediaIter(BatchJob job, Media media) {
            super(new JobPart(job, media, getMediaIndex(job, media), 0, 0));
        }

        public JobPartsFixedMediaIter(BatchJob job, Media media, int mediaIndex) {
            super(new JobPart(job, media, mediaIndex, 0, 0));
        }

        @Override
        protected JobPart computeNext(JobPart previous) {
            var job = previous.getJob();
            var pipelineParts = job.getPipelineElements();

            var nextActionIndex = previous.getActionIndex() + 1;
            if (previous.getTask().getActions().size() > nextActionIndex) {
                return new JobPart(
                        job,
                        previous.getMedia(),
                        previous.getMediaIndex(),
                        previous.getTaskIndex(),
                        nextActionIndex);
            }

            var nextTaskIdx = previous.getTaskIndex() + 1;
            if (pipelineParts.getTaskCount() > nextTaskIdx) {
                return new JobPart(
                        job,
                        previous.getMedia(),
                        previous.getMediaIndex(),
                        nextTaskIdx,
                        0);
            }

            return null;
        }
    }

    private static int getMediaIndex(BatchJob job, Media targetMedia) {
        int i = 0;
        for (var media : job.getMedia()) {
            if (media.getId() == targetMedia.getId()) {
                return i;
            }
            i++;
        }
        throw new IllegalArgumentException("targetMedia does not belong to job.");
    }
}
