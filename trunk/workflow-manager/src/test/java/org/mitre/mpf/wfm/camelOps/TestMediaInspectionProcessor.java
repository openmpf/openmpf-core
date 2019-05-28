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

package org.mitre.mpf.wfm.camelOps;

import org.apache.camel.Exchange;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mitre.mpf.test.TestUtil;
import org.mitre.mpf.wfm.camel.operations.mediainspection.MediaInspectionProcessor;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.transients.TransientMediaImpl;
import org.mitre.mpf.wfm.enums.BatchJobStatusType;
import org.mitre.mpf.wfm.enums.MpfHeaders;
import org.mitre.mpf.wfm.enums.UriScheme;
import org.mitre.mpf.wfm.util.IoUtils;
import org.mitre.mpf.wfm.util.JniLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mitre.mpf.test.TestUtil.*;
import static org.mockito.Mockito.*;

public class TestMediaInspectionProcessor {
	private static final Logger log = LoggerFactory.getLogger(TestMediaInspectionProcessor.class);
	private static final int MINUTES = 1000*60; // 1000 milliseconds/second & 60 seconds/minute.

    private final InProgressBatchJobsService mockInProgressJobs = mock(InProgressBatchJobsService.class);

    private final MediaInspectionProcessor mediaInspectionProcessor
            = new MediaInspectionProcessor(mockInProgressJobs, new IoUtils());


    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static int next() {
        return SEQUENCE.incrementAndGet();
    }

    @BeforeClass
    public static void initClass() {
        assertTrue(JniLoader.isLoaded());
    }


	@Test(timeout = 5 * MINUTES)
	public void testImageInspection() {
		log.info("Starting image inspection test.");
		long jobId = next();
		long mediaId = next();

        URI mediaUri = TestUtil.findFile("/samples/meds1.jpg");
        TransientMediaImpl transientMedia = new TransientMediaImpl(
                mediaId, mediaUri.toString(), UriScheme.get(mediaUri), Paths.get(mediaUri), Collections.emptyMap(),
                null);
        Exchange exchange = setupExchange(jobId, transientMedia);
        mediaInspectionProcessor.process(exchange);

        assertEquals("Media ID headers must be set.", mediaId, exchange.getOut().getHeader(MpfHeaders.MEDIA_ID));
        assertEquals("Job ID headers must be set.", jobId, exchange.getOut().getHeader(MpfHeaders.JOB_ID));

		assertFalse(String.format("The response entity must not fail. Actual: %s. Message: %s.",
				Boolean.toString(transientMedia.isFailed()),
				transientMedia.getMessage()),
				transientMedia.isFailed());

		String targetType = "image";
		int targetLength = 1;
		String targetHash = "c067e7eed23a0fe022140c30dbfa993ae720309d6567a803d111ecec739a6713";//`sha256sum meds1.jpg`

		verify(mockInProgressJobs)
                .addMediaInspectionInfo(eq(jobId), eq(mediaId), eq(targetHash), startsWith(targetType),
                                        eq(targetLength), notNull());
		verifyNoJobOrMediaError();

		log.info("Image inspection passed.");
	}


	/** Tests that the results from a video file are sane. */
	@Test(timeout = 5 * MINUTES)
	public void testVideoInspection() throws Exception {
		log.info("Starting video inspection test.");
        long jobId = next();
        long mediaId = next();

        URI mediaUri = TestUtil.findFile("/samples/video_01.mp4");
        TransientMediaImpl transientMedia = new TransientMediaImpl(
                mediaId, mediaUri.toString(), UriScheme.get(mediaUri), Paths.get(mediaUri), Collections.emptyMap(),
                null);
        Exchange exchange = setupExchange(jobId, transientMedia);
        mediaInspectionProcessor.process(exchange);

        assertEquals("Media ID headers must be set.", mediaId, exchange.getOut().getHeader(MpfHeaders.MEDIA_ID));
        assertEquals("Job ID headers must be set.", jobId, exchange.getOut().getHeader(MpfHeaders.JOB_ID));
        assertFalse(String.format("The response entity must not fail. Actual: %s. Message: %s.",
                        Boolean.toString(transientMedia.isFailed()),
                                        transientMedia.getMessage()),
                    transientMedia.isFailed());

        String targetType = "video";
        int targetLength = 90; //`ffprobe -show_packets video_01.mp4 | grep video | wc -l`
        String targetHash = "5eacf0a11d51413300ee0f4719b7ac7b52b47310a49320703c1d2639ebbc9fea"; //`sha256sum video_01.mp4`

        verify(mockInProgressJobs)
                .addMediaInspectionInfo(eq(jobId), eq(mediaId), eq(targetHash), startsWith(targetType),
                                        eq(targetLength), nonEmptyMap());

        verifyNoJobOrMediaError();

        log.info("Video inspection passed.");
    }


    /** Tests that the results from a video file are sane. */
    @Test(timeout = 5 * MINUTES)
    public void testVideoInspectionInvalid() {
        log.info("Starting invalid video inspection test.");
        long jobId = next();
        long mediaId = next();

        URI mediaUri = TestUtil.findFile("/samples/video_01_invalid.mp4");
        TransientMediaImpl transientMedia = new TransientMediaImpl(
                mediaId, mediaUri.toString(), UriScheme.get(mediaUri), Paths.get(mediaUri), Collections.emptyMap(),
                null);
        Exchange exchange = setupExchange(jobId, transientMedia);
        mediaInspectionProcessor.process(exchange);

        assertEquals("Media ID headers must be set.", mediaId, exchange.getOut().getHeader(MpfHeaders.MEDIA_ID));
        assertEquals("Job ID headers must be set.", jobId, exchange.getOut().getHeader(MpfHeaders.JOB_ID));

        verifyMediaError(jobId, mediaId);

        log.info("Media Inspection correctly handled error on invalid video file.");
    }


	/** Tests that the results from an audio file are sane. */
	@Test(timeout = 5 * MINUTES)
	public void testAudioInspection() {
		log.info("Starting audio inspection test.");
        long jobId = next();
        long mediaId = next();

        URI mediaUri = TestUtil.findFile("/samples/green.wav");
        TransientMediaImpl transientMedia = new TransientMediaImpl(
                mediaId, mediaUri.toString(), UriScheme.get(mediaUri), Paths.get(mediaUri), Collections.emptyMap(),
                null);
        Exchange exchange = setupExchange(jobId, transientMedia);
        mediaInspectionProcessor.process(exchange);

        assertEquals("Media ID headers must be set.", mediaId, exchange.getOut().getHeader(MpfHeaders.MEDIA_ID));
        assertEquals("Job ID headers must be set.", jobId, exchange.getOut().getHeader(MpfHeaders.JOB_ID));

        assertFalse(String.format("The response entity must not fail. Actual: %s. Message: %s.",
                        Boolean.toString(transientMedia.isFailed()),
                                 transientMedia.getMessage()),
                transientMedia.isFailed());

        String targetType = "audio";
        int targetLength = -1; //`ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 green.wav` - actually produces 2.200000
        String targetHash = "237739f8d6ff3459d747f79d272d148d156a696bad93f3ddecc2350c4ee5d9e0"; //`sha256sum green.wav`

        verify(mockInProgressJobs)
                .addMediaInspectionInfo(eq(jobId), eq(mediaId), eq(targetHash), startsWith(targetType),
                                        eq(targetLength), nonEmptyMap());

        verifyNoJobOrMediaError();

		log.info("Audio inspection passed.");
	}

	/** Tests that the results from a file which is not accessible is sane. */
	@Test(timeout = 5 * MINUTES)
	public void testInaccessibleFileInspection() throws Exception {
		log.info("Starting inaccessible file inspection test.");
        long jobId = next();
        long mediaId = next();

        URI mediaUri = URI.create("file:/asdfasfdasdf124124sadfasdfasdf.bin");
		TransientMediaImpl transientMedia = new TransientMediaImpl(
		        mediaId, mediaUri.toString(), UriScheme.get(mediaUri), Paths.get(mediaUri), Collections.emptyMap(),
                null);
        Exchange exchange = setupExchange(jobId, transientMedia);
        mediaInspectionProcessor.process(exchange);

        assertEquals("Media ID headers must be set.", mediaId, exchange.getOut().getHeader(MpfHeaders.MEDIA_ID));
        assertEquals("Job ID headers must be set.", jobId, exchange.getOut().getHeader(MpfHeaders.JOB_ID));

        assertTrue(transientMedia.isFailed());

        verifyMediaError(jobId, mediaId);

		log.info("Inaccessible file inspection passed.");
	}


	private void verifyNoJobOrMediaError() {
	    verify(mockInProgressJobs, never())
                .addMediaError(anyLong(), anyLong(), any());
	    verify(mockInProgressJobs, never())
                .setJobStatus(anyLong(), eq(BatchJobStatusType.ERROR));
    }

    private void verifyMediaError(long jobId, long mediaId) {
	    verify(mockInProgressJobs, atLeastOnce())
                .addMediaError(eq(jobId), eq(mediaId), nonBlank());

	    verify(mockInProgressJobs)
                .setJobStatus(jobId, BatchJobStatusType.ERROR);
    }

	private Exchange setupExchange(long jobId, TransientMediaImpl media) {
	    return MediaTestUtil.setupExchange(jobId, media, mockInProgressJobs);
    }
}
