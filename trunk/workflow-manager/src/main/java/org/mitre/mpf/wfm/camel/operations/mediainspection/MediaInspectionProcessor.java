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
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.external.ExternalParsersConfigReader;
import org.mitre.mpf.framecounter.FrameCounter;
import org.mitre.mpf.framecounter.NotReadableByOpenCvException;
import org.mitre.mpf.heic.HeicConverter;
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.camel.WfmProcessor;
import org.mitre.mpf.wfm.data.InProgressBatchJobsService;
import org.mitre.mpf.wfm.data.entities.persistent.Media;
import org.mitre.mpf.wfm.enums.BatchJobStatusType;
import org.mitre.mpf.wfm.enums.IssueCodes;
import org.mitre.mpf.wfm.enums.MediaType;
import org.mitre.mpf.wfm.enums.MpfHeaders;
import org.mitre.mpf.wfm.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

    private final InProgressBatchJobsService _inProgressJobs;

    private final IoUtils _ioUtils;

    private final MediaMetadataValidator _mediaMetadataValidator;

    @Inject
    public MediaInspectionProcessor(
            PropertiesUtil propertiesUtil, InProgressBatchJobsService inProgressJobs,
            IoUtils ioUtils, MediaMetadataValidator mediaMetadataValidator) {
        _propertiesUtil = propertiesUtil;
        _inProgressJobs = inProgressJobs;
        _ioUtils = ioUtils;
        _mediaMetadataValidator = mediaMetadataValidator;
    }

    @Override
    public void wfmProcess(Exchange exchange) throws WfmProcessingException {
        long jobId = exchange.getIn().getHeader(MpfHeaders.JOB_ID, Long.class);
        long mediaId = exchange.getIn().getHeader(MpfHeaders.MEDIA_ID, Long.class);

        Media media = _inProgressJobs.getJob(jobId).getMedia(mediaId);

        if(!media.isFailed()) {
            // Any request to pull a remote file should have already populated the local uri.
            assert media.getLocalPath() != null : "Media being processed by the MediaInspectionProcessor must have a local URI associated with them.";

            if (_mediaMetadataValidator.skipInspection(jobId, media)) {
                setHeaders(exchange, jobId, mediaId);
                return;
            }

            String sha = null;
            String mimeType = null;
            Map<String, String> mediaMetadata = new HashMap<>();
            int length = -1;
            MediaType mediaType = MediaType.UNKNOWN;

            try {
                Path localPath = media.getLocalPath();

                try (InputStream inputStream = Files.newInputStream(localPath)) {
                    LOG.debug("Calculating hash for '{}'.", localPath);
                    sha = DigestUtils.sha256Hex(inputStream);
                } catch (IOException ioe) {
                    String errorMessage = "Could not calculate the SHA-256 hash for the file due to IOException: "
                            + ioe;
                    _inProgressJobs.addError(jobId, mediaId, IssueCodes.ARTIFACT_EXTRACTION, errorMessage);
                    LOG.error(errorMessage, ioe);
                }

                mimeType = _ioUtils.getMimeType(localPath);

                mediaMetadata.put("MIME_TYPE", mimeType);
                mediaType = MediaTypeUtils.parse(mimeType);
                Metadata ffmpegMetadata = null;

                switch (mediaType) {
                    case IMAGE:
                        length = inspectImage(localPath, jobId, mediaId, mediaMetadata);
                        break;

                    case VIDEO:
                        ffmpegMetadata = generateFfmpegMetadata(localPath, mimeType);
                        String resolutionStr = ffmpegMetadata.get("videoResolution");
                        if (resolutionStr != null) {
                            length = inspectVideo(localPath, jobId, mediaId, mimeType, mediaMetadata, ffmpegMetadata);
                            break;
                        }
                        _inProgressJobs.addWarning(jobId, mediaId, IssueCodes.MISSING_VIDEO_STREAM,
                                                   "Cannot detect video resolution. Media may be missing video stream.");
                        mediaType = MediaType.AUDIO;
                        // fall through

                    case AUDIO:
                        if (ffmpegMetadata == null) {
                            ffmpegMetadata = generateFfmpegMetadata(localPath, mimeType);
                        }
                        String sampleRate = ffmpegMetadata.get("xmpDM:audioSampleRate");
                        if (sampleRate != null) {
                            length = inspectAudio(jobId, mediaId, mediaMetadata, ffmpegMetadata);
                            break;
                        }
                        _inProgressJobs.addWarning(jobId, mediaId, IssueCodes.MISSING_AUDIO_STREAM,
                                                   "Cannot detect audio file sample rate. Media may be missing audio stream.");
                        mediaType = MediaType.UNKNOWN;
                        // fall through

                    default:
                        LOG.warn("Treating job {}'s media {} as UNKNOWN data type.", jobId, mediaId);
                        break;
                }
            } catch (Exception e) {
                LOG.error("[Job {}|*|*] Failed to inspect {} due to an exception.", jobId, media.getUri(), e);
                if (e instanceof TikaException) {
                    _inProgressJobs.addError(jobId, mediaId, IssueCodes.MEDIA_INSPECTION,
                                             "Tika media inspection error: " + e.getMessage());
                } else {
                    _inProgressJobs.addError(jobId, mediaId, IssueCodes.MEDIA_INSPECTION, e.getMessage());
                }
            }

            _inProgressJobs.addMediaInspectionInfo(jobId, mediaId, sha, mediaType, mimeType, length, mediaMetadata);
            LOG.info("[Job {}] Media with URI {} (id={}) has data type {} and mime type {}.",
                     jobId, media.getUri(), media.getId(), media.getType(), media.getMimeType());
        } else {
            LOG.error("[Job {}|*|*] Skipping inspection of Media #{} as it is in an error state.", jobId, mediaId);
        }

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

    private int inspectAudio(long jobId, long mediaId, Map<String, String> mediaMetadata, Metadata ffmpegMetadata) {
        String durationStr = ffmpegMetadata.get("xmpDM:duration");
        if (durationStr == null) {
            _inProgressJobs.addError(jobId, mediaId, IssueCodes.MEDIA_INSPECTION,
                                     "Cannot detect audio file duration.");
            return -1;
        }

        int audioMilliseconds = calculateDurationMilliseconds(durationStr);
        if (audioMilliseconds >= 0) {
            mediaMetadata.put("DURATION", Integer.toString(audioMilliseconds));
        }
        return -1;
    }

    private int inspectVideo(Path localPath, long jobId, long mediaId, String mimeType,
                             Map<String, String> mediaMetadata, Metadata ffmpegMetadata)
            throws NotReadableByOpenCvException {

        int frameCount = getFrameCount(localPath, jobId, mediaId, mimeType, ffmpegMetadata);
        mediaMetadata.put("FRAME_COUNT", Integer.toString(frameCount));

        // FRAME_WIDTH and FRAME_HEIGHT

        String resolutionStr = ffmpegMetadata.get("videoResolution");
        if (resolutionStr == null) {
            _inProgressJobs.addError(jobId, mediaId, IssueCodes.MEDIA_INSPECTION,
                                     "Cannot detect video file resolution.");
            return -1;
        }

        String[] resolutionTokens = resolutionStr.split("x");
        int frameWidth = Integer.parseInt(resolutionTokens[0]);
        int frameHeight = Integer.parseInt(resolutionTokens[1]);
        mediaMetadata.put("FRAME_WIDTH", Integer.toString(frameWidth));
        mediaMetadata.put("FRAME_HEIGHT", Integer.toString(frameHeight));

        // FPS, DURATION, ROTATION, etc.

        String fpsStr = ffmpegMetadata.get("xmpDM:videoFrameRate");
        double fps = 0;
        if (fpsStr != null) {
            fps = Double.parseDouble(fpsStr);
            mediaMetadata.put("FPS", Double.toString(fps));
        }
        int duration = MediaInspectionProcessor.calculateDurationMilliseconds(ffmpegMetadata.get("xmpDM:duration"));
        if (duration <= 0 && fps > 0) {
            duration = (int) ((frameCount / fps) * 1000);
        }
        if (duration > 0) {
            mediaMetadata.put("DURATION", Integer.toString(duration));
        }

        String rotation = ffmpegMetadata.get("rotation");
        if (rotation != null) {
            mediaMetadata.put("ROTATION", rotation);
        }

        var frameTimeInfo = FrameTimeInfoBuilder.getFrameTimeInfo(localPath, fps);
        if (frameTimeInfo.hasConstantFrameRate()) {
            mediaMetadata.put("HAS_CONSTANT_FRAME_RATE", "true");
        }
        if (frameTimeInfo.requiresTimeEstimation()) {
            _inProgressJobs.addWarning(
                    jobId, mediaId, IssueCodes.MEDIA_INSPECTION,
                    "One or more presentation timestamp (PTS) values were missing from the " +
                            "video file. Some times in the output object will be estimated.");
        }

        _inProgressJobs.addFrameTimeInfo(jobId, mediaId, frameTimeInfo);
        return frameCount;
    }

    private int inspectImage(Path localPath, long jobId, long mediaId, Map<String, String> mediaMetadata)
            throws IOException, TikaException, SAXException {
        String mimeType = mediaMetadata.get("MIME_TYPE");

        Path mediaPath;
        if (mimeType.equalsIgnoreCase("image/heic")) {
            var tempDir = _propertiesUtil.getTemporaryMediaDirectory().toPath();
            mediaPath = tempDir.resolve(UUID.randomUUID() + ".png");
            LOG.info("{} is HEIC image. It will be converted to PNG.", localPath);
            HeicConverter.convert(localPath, mediaPath);
            _inProgressJobs.addConvertedMediaPath(jobId, mediaId, mediaPath);
        }
        else {
            mediaPath = localPath;
        }

        Metadata imageMetadata;
        try {
            imageMetadata = generateExifMetadata(localPath, mimeType);
        }
        catch (TikaException e) {
            if (!e.getMessage().contains("image/png parse error")
                    || !PngDefry.isCrushed(localPath)) {
                throw e;
            }
            LOG.info("Detected that \"{}\" is an Apple-optimized PNG. It will be converted to a " +
                             "regular PNG.",
                     localPath);
            var defriedPath = PngDefry.defry(localPath,
                                             _propertiesUtil.getTemporaryMediaDirectory().toPath());
            imageMetadata = generateExifMetadata(defriedPath, mimeType);
            _inProgressJobs.addConvertedMediaPath(jobId, mediaId, defriedPath);
            mediaPath = defriedPath;
        }

        String widthStr = imageMetadata.get("tiff:ImageWidth"); // jpeg, png
        if (widthStr == null) {
            widthStr = imageMetadata.get("Image Width"); // jpeg, webp
        }
        if (widthStr == null) {
            widthStr = imageMetadata.get("width"); // png
        }

        String heightStr = imageMetadata.get("tiff:ImageLength"); // jpeg, png
        if (heightStr == null) {
            heightStr = imageMetadata.get("Image Height"); // jpeg, webp
        }
        if (heightStr == null) {
            heightStr = imageMetadata.get("height"); // png
        }

        if (widthStr == null || heightStr == null) {
            // As a last resort, load the whole image into memory.
            BufferedImage bimg = ImageIO.read(mediaPath.toFile());
            if (bimg == null) {
                _inProgressJobs.addError(jobId, mediaId, IssueCodes.MEDIA_INSPECTION,
                                         "Cannot detect image file frame size. Cannot read image file.");
                return -1;
            }
            widthStr = Integer.toString(bimg.getWidth());
            heightStr = Integer.toString(bimg.getHeight());
        }
        mediaMetadata.put("FRAME_WIDTH", widthStr);
        mediaMetadata.put("FRAME_HEIGHT", heightStr);

        String orientationStr = imageMetadata.get("tiff:Orientation");
        if (orientationStr != null) {
            mediaMetadata.put("EXIF_ORIENTATION", orientationStr);
            int orientation = Integer.parseInt(orientationStr);
            switch (orientation) {
                case 1:
                    mediaMetadata.put("ROTATION", "0");
                    mediaMetadata.put("HORIZONTAL_FLIP", "FALSE");
                    break;
                case 2:
                    mediaMetadata.put("ROTATION", "0");
                    mediaMetadata.put("HORIZONTAL_FLIP", "TRUE");
                    break;
                case 3:
                    mediaMetadata.put("ROTATION", "180");
                    mediaMetadata.put("HORIZONTAL_FLIP", "FALSE");
                    break;
                case 4:
                    mediaMetadata.put("ROTATION", "180");
                    mediaMetadata.put("HORIZONTAL_FLIP", "TRUE");
                    break;
                case 5:
                    mediaMetadata.put("ROTATION", "90");
                    mediaMetadata.put("HORIZONTAL_FLIP", "TRUE");
                    break;
                case 6:
                    mediaMetadata.put("ROTATION", "90");
                    mediaMetadata.put("HORIZONTAL_FLIP", "FALSE");
                    break;
                case 7:
                    mediaMetadata.put("ROTATION", "270");
                    mediaMetadata.put("HORIZONTAL_FLIP", "TRUE");
                    break;
                case 8:
                    mediaMetadata.put("ROTATION", "270");
                    mediaMetadata.put("HORIZONTAL_FLIP", "FALSE");
                    break;
            }
        }
        return 1;
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


    /**
     * Gets the number of frames in the video using OpenCV and FFmpeg. If the frame counts are
     * different, the lower of the two will be returned.
     */
    private int getFrameCount(Path mediaPath, long jobId, long mediaId, String mimeType,
                              Metadata ffmpegMetadata) throws NotReadableByOpenCvException {
        LOG.info("Counting frames in '{}'.", mediaPath);

        int openCvFrameCount = -1;
        String openCvError = "";
        try {
            // We can't get the frame count directly from a gif,
            // so iterate over the frames and count them one by one
            boolean isGif = "image/gif".equals(mimeType);
            openCvFrameCount = new FrameCounter(mediaPath.toFile()).count(isGif);
        }
        catch (IOException | IllegalStateException e) {
            openCvError = "Failed to get frame count from OpenCV due to: " + e.getMessage();
            LOG.error(openCvError, e);
        }

        int ffmpegFrameCount = -1;
        String ffmpegError = "";
        try {
            var frameCountString = ffmpegMetadata.get("frameCount");
            if (StringUtils.isBlank(frameCountString)) {
                ffmpegError = "FFmpeg did not output the frame count.";
            }
            else {
                ffmpegFrameCount = Integer.parseInt(frameCountString);
            }
        }
        catch (NumberFormatException e) {
            ffmpegError = "Failed to get frame count from FFmpeg due to: " + e.getMessage();
            LOG.error(ffmpegError, e);
        }


        if (!ffmpegError.isEmpty() && !openCvError.isEmpty()) {
            _inProgressJobs.addError(jobId, mediaId, IssueCodes.FRAME_COUNT, ffmpegError);
            _inProgressJobs.addError(jobId, mediaId, IssueCodes.FRAME_COUNT, openCvError);
            return -1;
        }
        else if (!ffmpegError.isEmpty()) {
            _inProgressJobs.addWarning(jobId, mediaId, IssueCodes.FRAME_COUNT,
                                       String.format("%s However, OpenCV reported %s frames.",
                                                     ffmpegError, openCvFrameCount));
            return openCvFrameCount;
        }
        else if (!openCvError.isEmpty()) {
            _inProgressJobs.addWarning(jobId, mediaId, IssueCodes.FRAME_COUNT,
                                       String.format("%s However, FFmpeg reported %s frames.",
                                                     openCvError, ffmpegFrameCount));
            return ffmpegFrameCount;
        }
        else if (ffmpegFrameCount == openCvFrameCount) {
            return ffmpegFrameCount;
        }
        else {
            int frameCount = Math.min(ffmpegFrameCount, openCvFrameCount);
            String message = String.format("OpenCV reported the frame count to be %s, " +
                                           "but FFmpeg reported it to be %s. %s will be used.",
                                           openCvFrameCount, ffmpegFrameCount, frameCount);
            if (Math.abs(ffmpegFrameCount - openCvFrameCount) >= _propertiesUtil.getWarningFrameCountDiff()) {
                _inProgressJobs.addWarning(jobId, mediaId, IssueCodes.FRAME_COUNT, message);
            } else {
                LOG.warn(message);
            }
            return frameCount;
        }
    }
}
