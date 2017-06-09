/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2016 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2016 The MITRE Corporation                                       *
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

package org.mitre.mpf.mst;

import com.google.common.base.Stopwatch;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.mitre.mpf.interop.*;
import org.mitre.mpf.wfm.enums.MarkupStatus;
import org.mitre.mpf.wfm.event.JobProgress;
import org.mitre.mpf.wfm.service.MpfService;
import org.mitre.mpf.wfm.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 *
 * NOTE: Please keep the tests in this class in alphabetical order.  While they will automatically run that way regardless
 * of the order in the source code, keeping them in that order helps when correlating jenkins-produced output, which is
 * by job number, with named output, e.g., .../share/output-objects/2/detection.json and face/runFaceCombinedDetectImage.json
 *
 * This class contains tests that were formerly in TestEndToEndJenkins.  See comments in TestSystemOnDiff for information
 * about output checking
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TestSystemBatchVsStreamingJob extends TestSystemWithDefaultConfig {
    private static final Logger log = LoggerFactory.getLogger(TestSystemBatchVsStreamingJob.class);


    @Autowired
    private JsonUtils jsonUtils;

    @Autowired
    private MpfService mpfService;

    @Autowired
    private JobProgress jobProgress;


    // This test is the same test as run in TestSystemNightly, and is just run to confirm that batch jobs are still supported
    // after adding support for streaming jobs
    @Test(timeout = 5*MINUTES)
    public void runMotionTracking1() throws Exception {
        testCtr++;
        log.info(this.getClass().getName()+": Beginning test #{} runMotionTracking1()", testCtr);
        // When tracking is run on these videos it uses the STRUCK algorithm, which is non-deterministic, so there is
        // no output checking
        List<JsonMediaInputObject> media = toMediaObjectList(
                ioUtils.findFile("/samples/motion/five-second-marathon-clip.mkv"),
                ioUtils.findFile("/samples/person/video_02.mp4"));

        long jobId = runPipelineOnMedia("MOG MOTION DETECTION (WITH TRACKING) PIPELINE", media, Collections.emptyMap(),
                propertiesUtil.isOutputObjectsEnabled(), propertiesUtil.getJmsPriority());
        log.info(this.getClass().getName()+": Finished test runMotionTracking1()");
    }

    // This test is the same test as run in TestSystemNightly, and is just run to confirm that batch jobs are still supported
    // after adding support for streaming jobs
    @Test(timeout = 5*MINUTES)
    public void testNonUri() throws Exception {
        testCtr++;
        log.info(this.getClass().getName()+": eginning test #{} testNonUri()", testCtr);
        List<JsonMediaInputObject> media = new LinkedList<>();
        media.add(new JsonMediaInputObject("/not/a/file.txt"));
        long jobRequestId = runPipelineOnMedia("OCV PERSON DETECTION PIPELINE", media, Collections.emptyMap(),
                propertiesUtil.isOutputObjectsEnabled(), propertiesUtil.getJmsPriority());
        log.info(this.getClass().getName()+": Finished test testNonUri()");
    }

    // This test is the same test as run in TestSystemNightly, and is just run to confirm that batch jobs are still supported
    // after adding support for streaming jobs
    @Test(timeout = 20*MINUTES)
    public void runFaceOcvCustomDetectVideo() throws Exception {
        testCtr++;
        log.info(this.getClass().getName()+": Beginning test #{} runFaceOcvCustomDetectVideo()", testCtr);

        // set property MIN_FACE_SIZE=100 on the custom action "TEST X OCV FACE MIN FACE SIZE 100" to run the custom pipeline standard nightly test.
        // Note that this statement can be left as is when the default output object is created (i.e. when the default pipeline of
        // "OCV FACE DETECTION PIPELINE" is specified).  It doesn't have to be commented out
        // because that pipeline doen't use the custom action TEST X OCV FACE MIN FACE SIZE 100", it will
        // be using instead whatever task&action is defined for "OCV FACE DETECTION PIPELINE" pipeline in the component descriptor file
        String actionName = "TEST X OCV FACE MIN FACE SIZE 100";
        addAction(actionName, "FACECV", Collections.singletonMap("MIN_FACE_SIZE", "100"));

        String taskName = "TEST OCV FACE MIN FACE SIZE 100 TASK";
        addTask(taskName, actionName);

        String pipelineName = "TEST OCV FACE MIN FACE SIZE 100 PIPELINE"; 
        addPipeline(pipelineName, taskName);

        List<JsonMediaInputObject> media = toMediaObjectList(ioUtils.findFile("/samples/person/video_02.mp4"));
        long jobId = runPipelineOnMedia(pipelineName, media, Collections.emptyMap(), // use this line to generate output using the custom pipeline
//      long jobId = runPipelineOnMedia("OCV FACE DETECTION PIPELINE", media, Collections.emptyMap(),  // use this line to generate default output
                propertiesUtil.isOutputObjectsEnabled(), propertiesUtil.getJmsPriority());
        // Compare the normal Ocv pipeline output with this output.  The custom pipeline output should have fewer track sets
        // on this video (requires a video with some small faces)
        URI defaultOutputPath = (getClass().getClassLoader().getResource("output/face/runFaceOcvCustomDetectVideo-defaultCompare.json")).toURI();
        URI customOutputPath = propertiesUtil.createDetectionOutputObjectFile(jobId).toURI();

        JsonOutputObject defaultOutput = OBJECT_MAPPER.readValue(Files.readAllBytes(Paths.get(defaultOutputPath)), JsonOutputObject.class);
        JsonOutputObject customOutput = OBJECT_MAPPER.readValue(Files.readAllBytes(Paths.get(customOutputPath)), JsonOutputObject.class);

        Set<JsonMediaOutputObject> defMedias = defaultOutput.getMedia();
        Set<JsonMediaOutputObject> custMedias = customOutput.getMedia();

        // the number of media in the custom media group should be the same as the number in the default media group
        Assert.assertEquals(String.format("default MediaGroup size=%s doesn't match custom MediaGroup size=%s",
                defMedias.size(), custMedias.size()), defMedias.size(), custMedias.size());

        Iterator<JsonMediaOutputObject> defIter = defMedias.iterator();
        Iterator<JsonMediaOutputObject> custIter = custMedias.iterator();
        while(defIter.hasNext()){
            compareMedia(defIter.next(), custIter.next());
        }
        log.info(this.getClass().getName()+": Finished test runFaceOcvCustomDetectVideo()");
    }

    /**
     * For a given media item, compare the number of tracks from the default pipeline and the custom pipeline
     *
     * @param defaultMedia
     * @param customMedia
     */
    private void compareMedia(JsonMediaOutputObject defaultMedia, JsonMediaOutputObject customMedia) {

        Iterator<Map.Entry<String,SortedSet<JsonActionOutputObject>>> defaultEntries = defaultMedia.getTypes().entrySet().iterator();
        Iterator<Map.Entry<String,SortedSet<JsonActionOutputObject>>> customEntries = customMedia.getTypes().entrySet().iterator();

        while (defaultEntries.hasNext()) {

            Map.Entry<String, SortedSet<JsonActionOutputObject>> defaultAction = defaultEntries.next();
            Map.Entry<String, SortedSet<JsonActionOutputObject>> customAction = customEntries.next();
            Assert.assertEquals(String.format("Default action type %s does not match custom action type %s", defaultAction.getKey(), customAction.getKey()),
                    defaultAction.getKey(),
                    customAction.getKey());

            Iterator<JsonActionOutputObject> defaultTracks = defaultAction.getValue().iterator();
            Iterator<JsonActionOutputObject> customTracks = defaultAction.getValue().iterator();

            Assert.assertEquals(String.format("Default track entries size=%d doesn't match custom track entries size=%d",
                    defaultAction.getValue().size(), defaultAction.getValue().size()),
                    defaultAction.getValue().size(), defaultAction.getValue().size());
            while (customEntries.hasNext()) {
                SortedSet<JsonTrackOutputObject> cusTrackSet = customTracks.next().getTracks();
                SortedSet<JsonTrackOutputObject> defTrackSet = defaultTracks.next().getTracks();
                int cusTrackSetSize = cusTrackSet.size();
                int defTrackSetSize = defTrackSet.size();
//                log.debug("custom number of tracks={}", cusTrackSetSize);
//                log.debug("default number of tracks={}", defTrackSetSize);
                Assert.assertTrue(String.format("Custom number of tracks=%d is not less than default number of tracks=%d",
                        cusTrackSetSize, defTrackSetSize), cusTrackSetSize < defTrackSetSize);
            }
        }
    }


}
