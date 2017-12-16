package com.nagihong.videocompressor.audioProcessor;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import com.nagihong.videocompressor.MediaCodecBufferWrapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

public class AudioProcessor {

    private final String Tag = AudioProcessor.class.getName();

    private static class AudioBuffer {
        int bufferIndex;
        long presentationTimeUs;
        ShortBuffer data;
    }

    private static final int BYTES_PER_SHORT = 2;
    public static final int BUFFER_INDEX_END_OF_STREAM = -1;

    private static final long MICROSECS_PER_SEC = 1000000;

    private final Queue<AudioBuffer> emptyBuffers = new ArrayDeque<>();
    private final Queue<AudioBuffer> filledBuffers = new ArrayDeque<>();

    private final MediaCodecBufferWrapper decoderBuffers;
    private final MediaCodecBufferWrapper encoderBuffers;
    private final AudioBuffer overFlowBuffer = new AudioBuffer();

    private final MediaCodec decoder;
    private final MediaCodec encoder;
    private final MediaFormat encodeFormat;
    private MediaFormat actualDecodedFormat;

    private int inputSampleRate;
    private int inputChannelCount;
    private int outputChannelCount;

    private AudioRemixer remixer;

    public AudioProcessor(final MediaCodec decoder,
                          final MediaCodec encoder, final MediaFormat encodeFormat) {
        this.decoder = decoder;
        this.encoder = encoder;
        this.encodeFormat = encodeFormat;

        decoderBuffers = new MediaCodecBufferWrapper(this.decoder);
        encoderBuffers = new MediaCodecBufferWrapper(this.encoder);
    }

    /*
        setActualDecodedFormat() -> {
		    read KEY_SAMPLE_RATE;
		    read KEY_CHANNEL_COUNT from actualDecodedFormat;
		    read KEY_CHANNEL_COUNT from encoderFormat;

		    init(AudioRemixer);
	    }
     */
    public void setActualDecodedFormat(final MediaFormat decodedFormat) {
        Log.d(Tag, String.format("setActualDecodedFormat(%s)", null == decodedFormat ? "null" : "decodedFormat"));
        actualDecodedFormat = decodedFormat;

        //KEY_SAMPLE_RATE
        inputSampleRate = actualDecodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        if (inputSampleRate != encodeFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)) {
            throw new UnsupportedOperationException("Audio sample rate conversion not supported yet.");
        }

        //KEY_CHANNEL_COUNT
        inputChannelCount = actualDecodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        outputChannelCount = encodeFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        checkChannelCount(inputChannelCount);
        checkChannelCount(outputChannelCount);

        remixer = getRemixer(inputChannelCount, outputChannelCount);

        overFlowBuffer.presentationTimeUs = 0;
    }

    /*
        drainDecoderBufferAndQueue() -> {
		    buffer = decoderBuffers.getOutputBuffer();
		    emptyBuffer = emptyBuffers.poll();
		    copy (buffer) to (emptyBuffer, overflowBuffer)
		    filledBuffers.add(emptyBuffer);
	    }
     */
    public void drainDecoderBufferAndQueue(final int bufferIndex, final long presentationTimeUs) {
        checkActualDecodedFormat();

        final ByteBuffer data =
                bufferIndex == BUFFER_INDEX_END_OF_STREAM ? null : decoderBuffers.getOutputBuffer(bufferIndex);

        AudioBuffer buffer = pollEmptyBuffer();
        buffer.bufferIndex = bufferIndex;
        buffer.presentationTimeUs = presentationTimeUs;
        buffer.data = null == data ? null : data.asShortBuffer();

        if (null == overFlowBuffer.data && null != data) {
            overFlowBuffer.data = ByteBuffer
                    .allocateDirect(data.capacity()).order(ByteOrder.nativeOrder()).asShortBuffer();
            overFlowBuffer.data.clear().flip();
        }

        filledBuffers.add(buffer);
    }

    /*
        feedEncoder() -> {
		    buffer = encoder.dequeueInputBuffer();
		    reusedBuffer = encoderBuffers.getInputBuffer(buffer);
		    if(hasOverFlow) {
    			drainOverFlow(reusedBuffer);
	    		encoder.queueInputBuffer(reusedBuffer); 			return;
		    }
		    filledBuffer = filledBuffers.poll();
		    remixAndMaybeFillOverflow(filledBuffer, reusedBuffer);
		    encoder.queueInputBuffer(reusedBuffer);
		    decoder.releaseOutputBuffer(filledBuffer);
		    emptyBuffers.add(filledBuffer);
	    }
     */
    public boolean feedEncoder(long timeoutUs) {
        final boolean hasOverflow = hasOverflow();
        if (filledBuffers.isEmpty() && !hasOverflow) return false;

        final int encoderInBuffIndex = encoder.dequeueInputBuffer(timeoutUs);
        if (encoderInBuffIndex < 0) return false;

        final ShortBuffer outBuffer = encoderBuffers.getInputBuffer(encoderInBuffIndex).asShortBuffer();

        // Drain overflow first
        if (hasOverflow) {
            final long presentationTimeUs = drainOverflow(outBuffer);
            encoder.queueInputBuffer(encoderInBuffIndex,
                    0, outBuffer.position() * BYTES_PER_SHORT, presentationTimeUs, 0);
            return true;
        }

        //check EOS first
        final AudioBuffer inBuffer = filledBuffers.poll();
        if (inBuffer.bufferIndex == BUFFER_INDEX_END_OF_STREAM) {
            encoder.queueInputBuffer(encoderInBuffIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return false;
        }

        //feedEncoder
        /*
            这里实际上不需要合成outBuffer，因为outBuffer已经被consume了，主要是因为AudioRemixer的入参有两个
            而一个声音与一个静音合成，并不影响结果，所以就与outBuffer合成了
            同时与名字outBuffer的表义保持一致，数据写入到outBuffer中，然后放入到encoder作为inputBuffer
            所以上面的drainOverflow入参也是outBuffer
         */
        final long presentationTimeUs = remixAndMaybeFillOverflow(inBuffer, outBuffer);
        encoder.queueInputBuffer(encoderInBuffIndex, 0, outBuffer.position() * BYTES_PER_SHORT, presentationTimeUs, 0);
        if (inBuffer != null) {
            decoder.releaseOutputBuffer(inBuffer.bufferIndex, false);
            emptyBuffers.add(inBuffer);
        }

        return true;
    }

    /*
        drainOverflow(buffer) -> {
            put overFlowBuffer into buffer;
            clear overFlowBuffer;
        }
     */
    private long drainOverflow(final ShortBuffer outBuff) {
        final ShortBuffer overflowBuff = overFlowBuffer.data;
        final int overflowLimit = overflowBuff.limit();
        final int overflowSize = overflowBuff.remaining();

        /*
            overflow上的数据有两种
            第一种:
                直接是从decoder.outputBuffers出来的一份完整数据
            第二种:
                remixAndMaybeFillOverflow(inBuffer, outBuffer)过程中，剩余的部分

            所以此方法返回的时间是拿到多少数据，重新通过加法去计算presentationTimeUs
            而不是简单的通过overFlowBuffer.presentationTimeUs获取
         */
        final long beginPresentationTimeUs = overFlowBuffer.presentationTimeUs +
                sampleCountToDurationUs(overflowBuff.position(), inputSampleRate, outputChannelCount);

        outBuff.clear();
        // Limit overflowBuff to outBuff's capacity
        overflowBuff.limit(outBuff.capacity());
        // Load overflowBuff onto outBuff
        outBuff.put(overflowBuff);

        if (overflowSize >= outBuff.capacity()) {
            // Overflow fully consumed - Reset
            overflowBuff.clear().limit(0);
        } else {
            // Only partially consumed - Keep position & restore previous limit
            overflowBuff.limit(overflowLimit);
        }

        return beginPresentationTimeUs;
    }

    /*
        remixAndMaybeFillOverflow(inBuffer, outBuffer) -> {
            if(inBuffer.remaining() > outBuffer.remaining()) {
                remixer.remix(inBuffer, outBuffer);
                remixer.remix(inBuffer, overFlowBuffer);
            } else {
                remixer.remix(inBuffer, outBuffer);
            }
        }
     */
    private long remixAndMaybeFillOverflow(final AudioBuffer input,
                                           final ShortBuffer outBuff) {
        final ShortBuffer inBuff = input.data;
        final ShortBuffer overflowBuff = overFlowBuffer.data;

        outBuff.clear();

        // Reset position to 0, and set limit to capacity (Since MediaCodec doesn't do that for us)
        inBuff.clear();

        if (inBuff.remaining() > outBuff.remaining()) {
            // Overflow
            // Limit inBuff to outBuff's capacity
            inBuff.limit(outBuff.capacity());
            remixer.remix(inBuff, outBuff);

            // Reset limit to its own capacity & Keep position
            inBuff.limit(inBuff.capacity());

            // Remix the rest onto overflowBuffer
            // NOTE: We should only reach this point when overflow buffer is empty
            final long consumedDurationUs =
                    sampleCountToDurationUs(inBuff.position(), inputSampleRate, inputChannelCount);
            remixer.remix(inBuff, overflowBuff);

            // Seal off overflowBuff & mark limit
            overflowBuff.flip();
            overFlowBuffer.presentationTimeUs = input.presentationTimeUs + consumedDurationUs;
        } else {
            // No overflow
            remixer.remix(inBuff, outBuff);
        }

        return input.presentationTimeUs;
    }

    //========================= checkers ========================================================
    private void checkChannelCount(int channelCount) {
        if (channelCount != 1 && channelCount != 2) {
            throw new UnsupportedOperationException(String.format("channel count (%d) not supported.", channelCount));
        }
    }

    private void checkActualDecodedFormat() {
        if (null == actualDecodedFormat) {
            throw new RuntimeException("Buffer received before format!");
        }
    }

    private boolean hasOverflow() {
        return null != overFlowBuffer.data && overFlowBuffer.data.hasRemaining();
    }

    //========================= getters and setters ========================================================
    private AudioRemixer getRemixer(int inputChannelCount, int outputChannelCount) {
        if (inputChannelCount > outputChannelCount) {
            return AudioRemixer.DOWNMIX;
        } else if (inputChannelCount < outputChannelCount) {
            return AudioRemixer.UPMIX;
        } else {
            return AudioRemixer.PASSTHROUGH;
        }
    }

    private AudioBuffer pollEmptyBuffer() {
        AudioBuffer buffer = emptyBuffers.poll();
        if (buffer == null) {
            buffer = new AudioBuffer();
        }
        return buffer;
    }

    private static long sampleCountToDurationUs(final int sampleCount, final int sampleRate, final int channelCount) {
        return (sampleCount / (sampleRate * MICROSECS_PER_SEC)) / channelCount;
    }

}
