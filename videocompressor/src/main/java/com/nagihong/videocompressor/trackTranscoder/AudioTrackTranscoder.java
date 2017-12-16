package com.nagihong.videocompressor.trackTranscoder;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.nagihong.videocompressor.MediaCodecBufferWrapper;
import com.nagihong.videocompressor.audioProcessor.AudioProcessor;
import com.nagihong.videocompressor.transcoder.QueuedMuxer;

import java.io.IOException;

public class AudioTrackTranscoder implements TrackTranscoder {

    private static final QueuedMuxer.SampleType SAMPLE_TYPE = QueuedMuxer.SampleType.AUDIO;

    private static final int DRAIN_STATE_NONE = 0;
    private static final int DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY = 1;
    private static final int DRAIN_STATE_CONSUMED = 2;

    /**
     * MediaExtractor使用流程
     * extractor.setDataSource();
     * int expectTrackIndex;
     * extractor.getTrackFormat(expectTrackIndex);
     * extractor.selectTrack(expectTrackIndex);
     * extractor.readSampleData(buffer, ...);
     * long trackIndex = extractor.getSimpleTrackIndex();
     * long presentationTime = extractor.getSampleTime();
     * extractor.advance();
     * <p>
     * when finished
     * extractor.release();
     */
    private final MediaExtractor extractor;
    private final QueuedMuxer muxer;
    private long writtenPresentationTimeUs;

    private final int trackIndex;
    private final MediaFormat inputFormat;
    private final MediaFormat outputFormat;

    private final MediaCodec.BufferInfo bufferInfoCache = new MediaCodec.BufferInfo();
    private MediaCodec decoder;
    private MediaCodec encoder;
    private MediaFormat actualOutputFormat;

    private MediaCodecBufferWrapper decoderBuffers;
    private MediaCodecBufferWrapper encoderBuffers;

    private boolean isExtractorEOS;
    private boolean isDecoderEOS;
    private boolean isEncoderEOS;
    private boolean decoderStarted;
    private boolean encoderStarted;

    private AudioProcessor audioProcessor;

    public AudioTrackTranscoder(MediaExtractor extractor, int trackIndex,
                                MediaFormat outputFormat, QueuedMuxer muxer) {
        this.extractor = extractor;
        this.trackIndex = trackIndex;
        this.outputFormat = outputFormat;
        this.muxer = muxer;

        inputFormat = this.extractor.getTrackFormat(this.trackIndex);
    }

    @Override
    public void setup() {
        setupEncoder();
        setupDecoder();
        audioProcessor = new AudioProcessor(decoder, encoder, outputFormat);
    }

    private void setupEncoder() {
        try {
            encoder = MediaCodec.createEncoderByType(outputFormat.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        //MediaCodec.CONFIGURE_FLAG_ENCODE to tell this MediaCodec is an encoder, not a decoder
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        encoderStarted = true;
        encoderBuffers = new MediaCodecBufferWrapper(encoder);
    }

    private void setupDecoder() {
        final MediaFormat inputFormat = extractor.getTrackFormat(trackIndex);
        try {
            decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        decoder.configure(inputFormat, null, null, 0);
        decoder.start();
        decoderStarted = true;
        decoderBuffers = new MediaCodecBufferWrapper(decoder);
    }

    @Override
    public MediaFormat getDeterminedFormat() {
        return inputFormat;
    }

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

        //用while主要是有时候获取失败，立马重试
        while (audioProcessor.feedEncoder(0)) busy = true;
        while (drainExtractor(0) != DRAIN_STATE_NONE) busy = true;

        return busy;
    }

    private int drainExtractor(long timeoutUs) {
        //check eos
        if (isExtractorEOS) return DRAIN_STATE_NONE;
        //check trackIndex
        int trackIndex = extractor.getSampleTrackIndex();
        if (trackIndex >= 0 && trackIndex != this.trackIndex) {
            return DRAIN_STATE_NONE;
        }

        final int bufferIndex = decoder.dequeueInputBuffer(timeoutUs);
        if (bufferIndex < 0) return DRAIN_STATE_NONE;
        //mark eos
        if (trackIndex < 0) {
            //when track is not right, no need to proceed. mark eos to end transaction
            isExtractorEOS = true;
            decoder.queueInputBuffer(bufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return DRAIN_STATE_NONE;
        }

        //feed decoder
        final int sampleSize = extractor.readSampleData(decoderBuffers.getInputBuffer(bufferIndex), 0);
        final boolean isKeyFrame = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
        decoder.queueInputBuffer(bufferIndex, 0, sampleSize, extractor.getSampleTime(), isKeyFrame ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0);

        //step forward
        extractor.advance();
        return DRAIN_STATE_CONSUMED;
    }

    private int drainDecoder(long timeoutUs) {
        //check eos
        if (isDecoderEOS) return DRAIN_STATE_NONE;

        //get data from decoder and put in bufferInfoCache by params timeoutUs
        int bufferIndex = decoder.dequeueOutputBuffer(bufferInfoCache, timeoutUs);
        switch (bufferIndex) {
            case MediaCodec.INFO_TRY_AGAIN_LATER:
                return DRAIN_STATE_NONE;
            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                audioProcessor.setActualDecodedFormat(decoder.getOutputFormat());
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }

        //mark eos or feed encoder
        if ((bufferInfoCache.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            isDecoderEOS = true;
            audioProcessor.drainDecoderBufferAndQueue(AudioProcessor.BUFFER_INDEX_END_OF_STREAM, 0);
        } else if (bufferInfoCache.size > 0) {
            audioProcessor.drainDecoderBufferAndQueue(bufferIndex, bufferInfoCache.presentationTimeUs);
        }

        return DRAIN_STATE_CONSUMED;
    }

    private int drainEncoder(long timeoutUs) {
        //check eos
        if (isEncoderEOS) return DRAIN_STATE_NONE;

        //get output buffer, feed data into it and then feed encoder(reuse buffer to feed encoder)
        int bufferIndex = encoder.dequeueOutputBuffer(bufferInfoCache, timeoutUs);
        switch (bufferIndex) {
            case MediaCodec.INFO_TRY_AGAIN_LATER:
                return DRAIN_STATE_NONE;
            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                if (actualOutputFormat != null) {
                    throw new RuntimeException("Audio output format changed twice.");
                }
                actualOutputFormat = encoder.getOutputFormat();
                muxer.setOutputFormat(SAMPLE_TYPE, actualOutputFormat);
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                //this would only happens below api.21
                encoderBuffers = new MediaCodecBufferWrapper(encoder);
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }

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
        //drain encoder the mux into output file
        muxer.writeSampleData(SAMPLE_TYPE, encoderBuffers.getOutputBuffer(bufferIndex), bufferInfoCache);
        writtenPresentationTimeUs = bufferInfoCache.presentationTimeUs;
        encoder.releaseOutputBuffer(bufferIndex, false);
        return DRAIN_STATE_CONSUMED;
    }

    @Override
    public long getWrittenPresentationTimeUs() {
        return writtenPresentationTimeUs;
    }

    @Override
    public boolean isFinished() {
        return isEncoderEOS;
    }

    @Override
    public void release() {
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
