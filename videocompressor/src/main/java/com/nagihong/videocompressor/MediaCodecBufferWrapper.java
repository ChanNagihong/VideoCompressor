package com.nagihong.videocompressor;

import android.media.MediaCodec;
import android.os.Build;

import java.nio.ByteBuffer;

/**
 * wrapper for deprecated methods
 */
public class MediaCodecBufferWrapper {

    final MediaCodec mMediaCodec;
    final ByteBuffer[] mInputBuffers;
    final ByteBuffer[] mOutputBuffers;

    public MediaCodecBufferWrapper(MediaCodec mediaCodec) {
        mMediaCodec = mediaCodec;

        if (Build.VERSION.SDK_INT < 21) {
            mInputBuffers = mediaCodec.getInputBuffers();
            mOutputBuffers = mediaCodec.getOutputBuffers();
        } else {
            mInputBuffers = mOutputBuffers = null;
        }
    }

    public ByteBuffer getInputBuffer(final int index) {
        if (Build.VERSION.SDK_INT >= 21) {
            return mMediaCodec.getInputBuffer(index);
        }
        return mInputBuffers[index];
    }

    public ByteBuffer getOutputBuffer(final int index) {
        if (Build.VERSION.SDK_INT >= 21) {
            return mMediaCodec.getOutputBuffer(index);
        }
        return mOutputBuffers[index];
    }
}
