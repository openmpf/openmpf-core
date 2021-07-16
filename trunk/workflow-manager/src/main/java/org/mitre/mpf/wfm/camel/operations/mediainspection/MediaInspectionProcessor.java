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

package org.mitre.mpf.wfm.camel.operations.mediainspection;

import com.google.common.base.Preconditions;
import org.apache.camel.Exchange;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.external.ExternalParsersConfigReader;
import org.mitre.mpf.framecounter.FrameCounter;
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.camel.WfmProcessor;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.mitre.mpf.wfm.enums.BatchJobStatusType;
import org.mitre.mpf.wfm.enums.MpfHeaders;
import org.mitre.mpf.wfm.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;

/**
 * Class used to extract metadata about a piece of media. Media inspection will be skipped if the appropriate media
 * metadata properties are provided as job inputs.
 *
 * If a piece of media with a "video/*" MIME type has a video stream we will treat it as a VIDEO data type. Otherwise,
 * we determine if we can treat it as an AUDIO data type.
 *
 * If a piece of media with an "audio/*" MIME type (or "video/*" MIME type without an video stream) has an audio
 * stream we will treat it as an AUDIO data type. Otherwise, we will treat it as an UNKNOWN data type.
 *
 * To summarize, fallback is performed in this order: VIDEO --> AUDIO --> UNKNOWN. This is to handle cases where
 * a video container format can contain zero or more video/audio/subtitle/attachment/data streams.
 *
 * There is no fallback for the IMAGE data type. "image/*" MIME types are not containers like "video/*" MIME types.
 */
@Component(MediaInspectionProcessor.REF)
public class MediaInspectionProcessor extends WfmProcessor {
    public static final String REF = "mediaInspectionProcessor";

    private static final Logger LOG = LoggerFactory.getLogger(MediaInspectionProcessor.class);

    private final PropertiesUtil _propertiesUtil;

    private final MediaInspectionHelper _mediaInspectionHelper;

    private final InProgressBatchJobsService _inProgressJobs;

    private final IoUtils _ioUtils;

    private final MediaMetadataValidator _mediaMetadataValidator;

    @Inject
    public MediaInspectionProcessor(
            PropertiesUtil propertiesUtil, InProgressBatchJobsService inProgressJobs,
            IoUtils ioUtils, MediaMetadataValidator mediaMetadataValidator,
            MediaInspectionHelper mediaInspectionHelper) {
        _propertiesUtil = propertiesUtil;
        _inProgressJobs = inProgressJobs;
        _ioUtils = ioUtils;
        _mediaMetadataValidator = mediaMetadataValidator;
        _mediaInspectionHelper = mediaInspectionHelper;
    }

    @Override
    public void wfmProcess(Exchange exchange) throws WfmProcessingException {
        long jobId = exchange.getIn().getHeader(MpfHeaders.JOB_ID, Long.class);
        long mediaId = exchange.getIn().getHeader(MpfHeaders.MEDIA_ID, Long.class);

        Media media = _inProgressJobs.getJob(jobId).getMedia(mediaId);
        _mediaInspectionHelper.inspectMedia(media, jobId, mediaId);
        setHeaders(exchange, jobId, mediaId);

        if (media.isFailed()) {
            _inProgressJobs.setJobStatus(jobId, BatchJobStatusType.ERROR);
        }
    }
    private static void setHeaders(Exchange exchange, long jobId, long mediaId) {
        // Copy these headers to the output exchange.
        exchange.getOut().setHeader(MpfHeaders.CORRELATION_ID, exchange.getIn().getHeader(MpfHeaders.CORRELATION_ID));
        exchange.getOut().setHeader(MpfHeaders.SPLIT_SIZE, exchange.getIn().getHeader(MpfHeaders.SPLIT_SIZE));
        exchange.getOut().setHeader(MpfHeaders.JMS_PRIORITY, exchange.getIn().getHeader(MpfHeaders.JMS_PRIORITY));
        exchange.getOut().setHeader(MpfHeaders.JOB_ID, jobId);
        exchange.getOut().setHeader(MpfHeaders.MEDIA_ID, mediaId);
    }

    private Metadata generateFfmpegMetadata(Path path, String mimeType) throws IOException, TikaException,
            SAXException {
        Metadata metadata = new Metadata();
        try (InputStream stream = Preconditions.checkNotNull(TikaInputStream.get(path),
                "Cannot open file '%s'", path)) {
            metadata.set(Metadata.CONTENT_TYPE, mimeType);
            URL url = this.getClass().getClassLoader().getResource("tika-external-parsers.xml");
            Parser parser = ExternalParsersConfigReader.read(url.openStream()).get(0);
            parser.parse(stream, new DefaultHandler(), metadata, new ParseContext());
        }
        return metadata;
    }

    private static Metadata generateExifMetadata(Path path, String mimeType) throws IOException, TikaException, SAXException {
        Metadata metadata = new Metadata();
        try (InputStream stream = Preconditions.checkNotNull(TikaInputStream.get(path),
                "Cannot open file '%s'", path)) {
            metadata.set(Metadata.CONTENT_TYPE, mimeType);

            Parser parser = mimeType.equals("image/jpeg")
                    ? new CustomJpegParser()
                    : new AutoDetectParser();
            parser.parse(stream, new DefaultHandler(), metadata, new ParseContext());
        }
        return metadata;
    }

    private static int calculateDurationMilliseconds(String durationStr) {
        if (durationStr != null) {
            String[] durationArray = durationStr.split("\\.|:");
            int hours = Integer.parseInt(durationArray[0]);
            int minutes = Integer.parseInt(durationArray[1]);
            int seconds = Integer.parseInt(durationArray[2]);
            int milliseconds = Integer.parseInt(durationArray[3]);
            milliseconds = milliseconds + 1000 * seconds + 1000 * 60 * minutes + 1000 * 60 * 60 * hours;
            return milliseconds;
        }
        return -1;
    }
}
