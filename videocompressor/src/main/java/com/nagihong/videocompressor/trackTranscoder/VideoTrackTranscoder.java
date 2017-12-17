package com.nagihong.videocompressor.trackTranscoder;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.nagihong.videocompressor.MediaCodecBufferWrapper;
import com.nagihong.videocompressor.strategies.MediaFormatExtraConstants;
import com.nagihong.videocompressor.system.InputSurface;
import com.nagihong.videocompressor.system.OutputSurface;
import com.nagihong.videocompressor.transcoder.QueuedMuxer;

import java.io.IOException;

// Refer: https://android.googlesource.com/platform/cts/+/lollipop-release/tests/tests/media/src/android/media/cts/ExtractDecodeEditEncodeMuxTest.java
public class VideoTrackTranscoder implements TrackTranscoder {
    private static final int DRAIN_STATE_NONE = 0;
    private static final int DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY = 1;
    private static final int DRAIN_STATE_CONSUMED = 2;

    private final MediaExtractor extractor;
    private final int trackIndex;
    private final MediaFormat outputFormat;
    private MediaFormat actualOutputFormat;
    private final QueuedMuxer muxer;

    private final MediaCodec.BufferInfo bufferInfoCache = new MediaCodec.BufferInfo();

    private MediaCodec decoder;
    private MediaCodec encoder;
    private MediaCodecBufferWrapper decoderBuffers;
    private MediaCodecBufferWrapper encoderBuffers;

    private OutputSurface decoderOutputSurfaceWrapper;
    private InputSurface encoderInputSurfaceWrapper;

    private boolean isExtractorEOS;
    private boolean isDecoderEOS;
    private boolean isEncoderEOS;
    private boolean decoderStarted;
    private boolean encoderStarted;
    private long writtenPresentationTimeUs;

    public VideoTrackTranscoder(MediaExtractor extractor, int trackIndex,
                                MediaFormat outputFormat, QueuedMuxer muxer) {
        this.extractor = extractor;
        this.trackIndex = trackIndex;
        this.outputFormat = outputFormat;
        this.muxer = muxer;
    }

    //========================= setup ========================================================
    @Override
    public void setup() {
        setupEncoder();
        setupDecoder();
    }

    private void setupEncoder() {
        try {
            encoder = MediaCodec.createEncoderByType(outputFormat.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        //note that the usage of surface for encoder not the same to decoder
        encoderInputSurfaceWrapper = new InputSurface(encoder.createInputSurface());
        encoderInputSurfaceWrapper.makeCurrent();
        encoder.start();
        encoderStarted = true;
        encoderBuffers = new MediaCodecBufferWrapper(encoder);
    }

    private void setupDecoder() {
        MediaFormat inputFormat = extractor.getTrackFormat(trackIndex);
        if (inputFormat.containsKey(MediaFormatExtraConstants.KEY_ROTATION_DEGREES)) {
            // Decoded video is rotated automatically in Android 5.0 lollipop.
            // Turn off here because we don't want to encode rotated one.
            // refer: https://android.googlesource.com/platform/frameworks/av/+blame/lollipop-release/media/libstagefright/Utils.cpp
            inputFormat.setInteger(MediaFormatExtraConstants.KEY_ROTATION_DEGREES, 0);
        }
        decoderOutputSurfaceWrapper = new OutputSurface();
        try {
            decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        decoder.configure(inputFormat, decoderOutputSurfaceWrapper.getSurface(), null, 0);
        decoder.start();
        decoderStarted = true;
        decoderBuffers = new MediaCodecBufferWrapper(decoder);
    }

    //========================= transcoding ========================================================
    @Override
    public boolean stepPipeline() {
        boolean busy = false;

        int status;
        //用while主要是有时候获取失败，立马重试
        while (drainEncoder(0) != DRAIN_STATE_NONE) busy = true;
        do {
            status = drainDecoder(0);
            if (status != DRAIN_STATE_NONE) busy = true;
            // NOTE: not repeating to keep from deadlock when encoder is full.
        } while (status == DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY);
        //用while主要是有时候获取失败，立马重试, means when drainExtractor return DRAIN_STATE_CONSUMED, result is satisfied, break the while loop
        while (drainExtractor(0) != DRAIN_STATE_NONE) busy = true;

        return busy;
    }

    private int drainExtractor(long timeoutUs) {
        if (isExtractorEOS) return DRAIN_STATE_NONE;

        //check trackIndex whether correct
        int trackIndex = extractor.getSampleTrackIndex();
        if (trackIndex >= 0 && trackIndex != this.trackIndex) {
            return DRAIN_STATE_NONE;
        }
        //check trackIndex legality and bufferIndex
        int bufferIndex = decoder.dequeueInputBuffer(timeoutUs);
        if (bufferIndex < 0) return DRAIN_STATE_NONE;
        if (trackIndex < 0) {
            isExtractorEOS = true;
            decoder.queueInputBuffer(bufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return DRAIN_STATE_NONE;
        }

        //drainExtractor
        int sampleSize = extractor.readSampleData(decoderBuffers.getInputBuffer(bufferIndex), 0);
        boolean isKeyFrame = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
        decoder.queueInputBuffer(bufferIndex, 0, sampleSize, extractor.getSampleTime(), isKeyFrame ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0);
        extractor.advance();
        return DRAIN_STATE_CONSUMED;
    }

    private int drainDecoder(long timeoutUs) {
        if (isDecoderEOS) return DRAIN_STATE_NONE;
        int bufferIndex = decoder.dequeueOutputBuffer(bufferInfoCache, timeoutUs);
        //check bufferIndex
        switch (bufferIndex) {
            case MediaCodec.INFO_TRY_AGAIN_LATER:
                return DRAIN_STATE_NONE;
            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }
        //check eos
        if ((bufferInfoCache.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            encoder.signalEndOfInputStream();
            isDecoderEOS = true;
            bufferInfoCache.size = 0;
        }

        //drainDecoder
        boolean doRender = (bufferInfoCache.size > 0);
        // NOTE: doRender will block if buffer (of encoder) is full.
        // Refer: http://bigflake.com/mediacodec/CameraToMpegTest.java.txt
        decoder.releaseOutputBuffer(bufferIndex, doRender);
        if (doRender) {
            decoderOutputSurfaceWrapper.awaitNewImage();
            decoderOutputSurfaceWrapper.drawImage();
            encoderInputSurfaceWrapper.setPresentationTime(bufferInfoCache.presentationTimeUs * 1000);
            encoderInputSurfaceWrapper.swapBuffers();
        }
        return DRAIN_STATE_CONSUMED;
    }

    private int drainEncoder(long timeoutUs) {
        if (isEncoderEOS) return DRAIN_STATE_NONE;

        int bufferIndex = encoder.dequeueOutputBuffer(bufferInfoCache, timeoutUs);
        //check bufferIndex
        switch (bufferIndex) {
            case MediaCodec.INFO_TRY_AGAIN_LATER:
                return DRAIN_STATE_NONE;
            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                if (actualOutputFormat != null)
                    throw new RuntimeException("Video output format changed twice.");
                actualOutputFormat = encoder.getOutputFormat();
                muxer.setOutputFormat(QueuedMuxer.SampleType.VIDEO, actualOutputFormat);
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                encoderBuffers = new MediaCodecBufferWrapper(encoder);
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }
        //check format
        if (actualOutputFormat == null) {
            throw new RuntimeException("Could not determine actual output format.");
        }
        //check eos
        if ((bufferInfoCache.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            isEncoderEOS = true;
            bufferInfoCache.set(0, 0, 0, bufferInfoCache.flags);
        }
        //check buffer contains other config data instead of media data, when meet it, do retry to get another buffer
        if ((bufferInfoCache.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // SPS or PPS, which should be passed by MediaFormat.
            encoder.releaseOutputBuffer(bufferIndex, false);
            return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }

        //drainEncoder
        muxer.writeSampleData(QueuedMuxer.SampleType.VIDEO, encoderBuffers.getOutputBuffer(bufferIndex), bufferInfoCache);
        writtenPresentationTimeUs = bufferInfoCache.presentationTimeUs;
        encoder.releaseOutputBuffer(bufferIndex, false);
        return DRAIN_STATE_CONSUMED;
    }

    //========================= getters and setters ========================================================
    @Override
    public long getWrittenPresentationTimeUs() {
        return writtenPresentationTimeUs;
    }

    @Override
    public boolean isFinished() {
        return isEncoderEOS;
    }

    @Override
    public MediaFormat getDeterminedFormat() {
        return actualOutputFormat;
    }

    //========================= 特殊业务 ========================================================
    // TODO: CloseGuard
    @Override
    public void release() {
        if (decoderOutputSurfaceWrapper != null) {
            decoderOutputSurfaceWrapper.release();
            decoderOutputSurfaceWrapper = null;
        }
        if (encoderInputSurfaceWrapper != null) {
            encoderInputSurfaceWrapper.release();
            encoderInputSurfaceWrapper = null;
        }
        if (decoder != null) {
            if (decoderStarted) decoder.stop();
            decoder.release();
            decoder = null;
        }
        if (encoder != null) {
            if (encoderStarted) encoder.stop();
            encoder.release();
            encoder = null;
        }
    }
}
