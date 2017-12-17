package com.nagihong.videocompressor.trackTranscoder;

import android.media.MediaFormat;

/*
    TrackTranscoder -> {
	setup() -> {
		createEncoder;
		configureEncoder;
		encoderBuffers = new MediaCodecBufferCompatWrapper();
		startEncoder;

		createDecoder;
		configureDecoder;
		decoderBuffers = new MediaCodecBufferCompatWrapper();
		startDecoder;

		init AudioChannel; (when in AudioTrackTranscoder)
	}

	stepPipeline() -> {
		drainEncoder();
		drainDecoder();
		AudioChannel.feedEncoder(); (when in AudioTrackTranscoder)
		drainExtractor();
	}

	drainExtractor() -> {
		buffer = decoder.dequeueInputBuffer();
		extractor.readSampleData() into buffer;
		decoder.queueInputBuffer(buffer);
	}

	drainDecoder() -> {
		buffer = decoder.dequeueOutputBuffer();
		AudioChannel.drainDecoderBufferAndQueue(buffer); (when in AudioTrackTranscoder)
		decoder.releaseOutputBuffer(buffer); (when in VideoTrackTranscoder)
	}

	drainEncoder() -> {
		buffer = encoder.dequeueOutputBuffer();
		QueuedMuxer.writeSampleData(buffer);
		encoder.releaseOutputBuffer(buffer);
	}
}
 */
public interface TrackTranscoder {

    void setup();

    /**
     * Get actual MediaFormat which is used to write to muxer.
     * To determine you should call {@link #stepPipeline()} several times.
     * @return Actual output format determined by coder, or {@code null} if not yet determined.
     */
    MediaFormat getDeterminedFormat();

    /**
     * Step pipeline if output is available in any step of it.
     * It assumes muxer has been started, so you should call muxer.start() first.
     *
     * @return true if data moved in pipeline.
     */
    boolean stepPipeline();

    /**
     * Get presentation time of last sample written to muxer.
     *
     * @return Presentation time in micro-second. Return value is undefined if finished writing.
     */
    long getWrittenPresentationTimeUs();

    boolean isFinished();

    void release();
}
