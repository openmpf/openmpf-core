package org.mitre.mpf.mst;

import org.junit.Assert;
import org.junit.Test;
import org.mitre.mpf.interop.JsonMediaInputObject;
import org.mitre.mpf.interop.JsonOutputObject;

import java.util.Collection;
import java.util.List;

public class TestSystemCodecs extends TestSystemWithDefaultConfig {

    @Test(timeout = 5 * MINUTES)
    public void runFaceOcvDetectImageAMR() {
        String pipelineName = "SPHINX SPEECH DETECTION PIPELINE";
        addPipeline(pipelineName);

        List<JsonMediaInputObject> media = toMediaObjectList(ioUtils.findFile("/samples/speech/amrnb.amr"));

        long jobId = runPipelineOnMedia(pipelineName, media);
        JsonOutputObject outputObject = getJobOutputObject(jobId);

        boolean detectionFound = outputObject.getMedia()
                .stream()
                .flatMap(m -> m.getTypes().values().stream())
                .flatMap(Collection::stream)
                .flatMap(a -> a.getTracks().stream())
                .flatMap(t -> t.getDetections().stream())
                .findAny()
                .isPresent();

        Assert.assertTrue(detectionFound);
    }

    @Test(timeout = 5 * MINUTES)
    public void runFaceOcvDetectImageSpeex() {
        String pipelineName = "SPHINX SPEECH DETECTION PIPELINE";
        addPipeline(pipelineName);

        List<JsonMediaInputObject> media = toMediaObjectList(ioUtils.findFile("/samples/speech/speex.spx"));

        long jobId = runPipelineOnMedia(pipelineName, media);
        JsonOutputObject outputObject = getJobOutputObject(jobId);

        boolean detectionFound = outputObject.getMedia()
                .stream()
                .flatMap(m -> m.getTypes().values().stream())
                .flatMap(Collection::stream)
                .flatMap(a -> a.getTracks().stream())
                .flatMap(t -> t.getDetections().stream())
                .findAny()
                .isPresent();

        Assert.assertTrue(detectionFound);
    }

    @Test(timeout = 5 * MINUTES)
    public void runFaceOcvDetectImageH264AAC() {
        String pipelineName = "OCV FACE DETECTION PIPELINE";
        addPipeline(pipelineName);

        List<JsonMediaInputObject> media = toMediaObjectList(ioUtils.findFile("/samples/face/h264_aac.mp4"));

        long jobId = runPipelineOnMedia(pipelineName, media);
        JsonOutputObject outputObject = getJobOutputObject(jobId);

        boolean detectionFound = outputObject.getMedia()
                .stream()
                .flatMap(m -> m.getTypes().values().stream())
                .flatMap(Collection::stream)
                .flatMap(a -> a.getTracks().stream())
                .flatMap(t -> t.getDetections().stream())
                .findAny()
                .isPresent();

        Assert.assertTrue(detectionFound);
    }

    @Test(timeout = 5 * MINUTES)
    public void runFaceOcvDetectImageH264MP3() {
        String pipelineName = "OCV FACE DETECTION PIPELINE";
        addPipeline(pipelineName);

        List<JsonMediaInputObject> media = toMediaObjectList(ioUtils.findFile("/samples/face/h264_mp3.mp4"));

        long jobId = runPipelineOnMedia(pipelineName, media);
        JsonOutputObject outputObject = getJobOutputObject(jobId);

        boolean detectionFound = outputObject.getMedia()
                .stream()
                .flatMap(m -> m.getTypes().values().stream())
                .flatMap(Collection::stream)
                .flatMap(a -> a.getTracks().stream())
                .flatMap(t -> t.getDetections().stream())
                .findAny()
                .isPresent();

        Assert.assertTrue(detectionFound);
    }

    @Test(timeout = 5 * MINUTES)
    public void runFaceOcvDetectImageH265AAC() {
        String pipelineName = "OCV FACE DETECTION PIPELINE";
        addPipeline(pipelineName);

        List<JsonMediaInputObject> media = toMediaObjectList(ioUtils.findFile("/samples/face/h265_mp3.mp4"));

        long jobId = runPipelineOnMedia(pipelineName, media);
        JsonOutputObject outputObject = getJobOutputObject(jobId);

        boolean detectionFound = outputObject.getMedia()
                .stream()
                .flatMap(m -> m.getTypes().values().stream())
                .flatMap(Collection::stream)
                .flatMap(a -> a.getTracks().stream())
                .flatMap(t -> t.getDetections().stream())
                .findAny()
                .isPresent();

        Assert.assertTrue(detectionFound);
    }

    @Test(timeout = 5 * MINUTES)
    public void runFaceOcvDetectImageXvidAAC() {
        String pipelineName = "OCV FACE DETECTION PIPELINE";
        addPipeline(pipelineName);

        List<JsonMediaInputObject> media = toMediaObjectList(ioUtils.findFile("/samples/face/xvid_aac.mp4"));

        long jobId = runPipelineOnMedia(pipelineName, media);
        JsonOutputObject outputObject = getJobOutputObject(jobId);

        boolean detectionFound = outputObject.getMedia()
                .stream()
                .flatMap(m -> m.getTypes().values().stream())
                .flatMap(Collection::stream)
                .flatMap(a -> a.getTracks().stream())
                .flatMap(t -> t.getDetections().stream())
                .findAny()
                .isPresent();

        Assert.assertTrue(detectionFound);
    }

    @Test(timeout = 5 * MINUTES)
    public void runFaceOcvDetectImageTheoraOpus() {
        String pipelineName = "OCV FACE DETECTION PIPELINE";
        addPipeline(pipelineName);

        List<JsonMediaInputObject> media = toMediaObjectList(ioUtils.findFile("/samples/face/theora_opus.ogg"));

        long jobId = runPipelineOnMedia(pipelineName, media);
        JsonOutputObject outputObject = getJobOutputObject(jobId);

        boolean detectionFound = outputObject.getMedia()
                .stream()
                .flatMap(m -> m.getTypes().values().stream())
                .flatMap(Collection::stream)
                .flatMap(a -> a.getTracks().stream())
                .flatMap(t -> t.getDetections().stream())
                .findAny()
                .isPresent();

        Assert.assertTrue(detectionFound);
    }

    @Test(timeout = 5 * MINUTES)
    public void runFaceOcvDetectImageTheoraVorbis() {
        String pipelineName = "OCV FACE DETECTION PIPELINE";
        addPipeline(pipelineName);

        List<JsonMediaInputObject> media = toMediaObjectList(ioUtils.findFile("/samples/face/theora_vorbis.ogg"));

        long jobId = runPipelineOnMedia(pipelineName, media);
        JsonOutputObject outputObject = getJobOutputObject(jobId);

        boolean detectionFound = outputObject.getMedia()
                .stream()
                .flatMap(m -> m.getTypes().values().stream())
                .flatMap(Collection::stream)
                .flatMap(a -> a.getTracks().stream())
                .flatMap(t -> t.getDetections().stream())
                .findAny()
                .isPresent();

        Assert.assertTrue(detectionFound);
    }

    @Test(timeout = 5 * MINUTES)
    public void runFaceOcvDetectImageVp8Vorbis() {
        String pipelineName = "OCV FACE DETECTION PIPELINE";
        addPipeline(pipelineName);

        List<JsonMediaInputObject> media = toMediaObjectList(ioUtils.findFile("/samples/face/vp8_vorbis.ogg"));

        long jobId = runPipelineOnMedia(pipelineName, media);
        JsonOutputObject outputObject = getJobOutputObject(jobId);

        boolean detectionFound = outputObject.getMedia()
                .stream()
                .flatMap(m -> m.getTypes().values().stream())
                .flatMap(Collection::stream)
                .flatMap(a -> a.getTracks().stream())
                .flatMap(t -> t.getDetections().stream())
                .findAny()
                .isPresent();

        Assert.assertTrue(detectionFound);
    }

}