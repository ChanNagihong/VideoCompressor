package com.nagihong.videocompressor.transcoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * not working until all output track formats are determined
 */
/*
    MediaMuxer使用方法
      MediaMuxer muxer = new MediaMuxer(..., OutputFormat.MUXER_OUTPUT_MPEG_4);
      int audioTrackIndex = muxer.addTrack(audioFormat);
      int videoTrackIndex = muxer.addTrack(videoFormat);
      ByteBuffer inputBuffer = ByteBuffer.allocate(bufferSize);
      BufferInfo bufferInfo = new BufferInfo();

      muxer.start();
      while(!finished) {
          finished = getInputBuffer(inputBuffer, isAudioSample, bufferInfo);
          if(!finished) {
              int currentTrackIndex = isAudioSample ? audioTrackIndex : videoTrackIndex;
              muxer.writeSampleData(currentTrackIndex, inputBuffer, bufferInfo);
          }
      }
      muxer.stop();
      muxer.release();
 */
public class QueuedMuxer {
    private static final String Tag = QueuedMuxer.class.getSimpleName();
    private static final int BUFFER_SIZE = 64 * 1024;
    private final MediaMuxer mMuxer;
    private final Listener mListener;
    private MediaFormat mVideoFormat;
    private MediaFormat mAudioFormat;
    private int mVideoTrackIndex;
    private int mAudioTrackIndex;
    private ByteBuffer mByteBuffer;
    private final List<SampleInfo> mSampleInfoList;
    private boolean mStarted;

    public QueuedMuxer(MediaMuxer muxer, Listener listener) {
        mMuxer = muxer;
        mListener = listener;
        mSampleInfoList = new ArrayList<>();
    }

    /**
     * determine track format
     * then {@link #writeSampleData(SampleType, ByteBuffer, MediaCodec.BufferInfo)} will work
     */
    public void setOutputFormat(SampleType sampleType, MediaFormat format) {
        switch (sampleType) {
            case VIDEO:
                mVideoFormat = format;
                break;
            case AUDIO:
                mAudioFormat = format;
                break;
            default:
                throw new AssertionError();
        }
        onSetOutputFormat();
    }

    private void onSetOutputFormat() {
        if (mVideoFormat == null || mAudioFormat == null) return;
        mListener.onDetermineOutputFormat();

        mVideoTrackIndex = mMuxer.addTrack(mVideoFormat);
        Log.v(Tag, "Added track #" + mVideoTrackIndex + " with " + mVideoFormat.getString(MediaFormat.KEY_MIME) + " to muxer");
        mAudioTrackIndex = mMuxer.addTrack(mAudioFormat);
        Log.v(Tag, "Added track #" + mAudioTrackIndex + " with " + mAudioFormat.getString(MediaFormat.KEY_MIME) + " to muxer");
        mMuxer.start();
        mStarted = true;

        if (mByteBuffer == null) {
            mByteBuffer = ByteBuffer.allocate(0);
        }
        mByteBuffer.flip();
        Log.v(Tag, "Output format determined, writing " + mSampleInfoList.size() +
                " samples / " + mByteBuffer.limit() + " bytes to muxer.");
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int offset = 0;
        for (SampleInfo sampleInfo : mSampleInfoList) {
            sampleInfo.writeToBufferInfo(bufferInfo, offset);
            mMuxer.writeSampleData(getTrackIndexForSampleType(sampleInfo.mSampleType), mByteBuffer, bufferInfo);
            offset += sampleInfo.mSize;
        }
        mSampleInfoList.clear();
        mByteBuffer = null;
    }

    public void writeSampleData(SampleType sampleType, ByteBuffer byteBuf, MediaCodec.BufferInfo bufferInfo) {
        if (mStarted) {
            mMuxer.writeSampleData(getTrackIndexForSampleType(sampleType), byteBuf, bufferInfo);
            return;
        }
        byteBuf.limit(bufferInfo.offset + bufferInfo.size);
        byteBuf.position(bufferInfo.offset);
        if (mByteBuffer == null) {
            mByteBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE).order(ByteOrder.nativeOrder());
        }
        mByteBuffer.put(byteBuf);
        mSampleInfoList.add(new SampleInfo(sampleType, bufferInfo.size, bufferInfo));
    }

    private int getTrackIndexForSampleType(SampleType sampleType) {
        switch (sampleType) {
            case VIDEO:
                return mVideoTrackIndex;
            case AUDIO:
                return mAudioTrackIndex;
            default:
                throw new AssertionError();
        }
    }

    public enum SampleType {VIDEO, AUDIO}

    private static class SampleInfo {
        private final SampleType mSampleType;
        private final int mSize;
        private final long mPresentationTimeUs;
        private final int mFlags;

        private SampleInfo(SampleType sampleType, int size, MediaCodec.BufferInfo bufferInfo) {
            mSampleType = sampleType;
            mSize = size;
            mPresentationTimeUs = bufferInfo.presentationTimeUs;
            mFlags = bufferInfo.flags;
        }

        private void writeToBufferInfo(MediaCodec.BufferInfo bufferInfo, int offset) {
            bufferInfo.set(offset, mSize, mPresentationTimeUs, mFlags);
        }
    }

    public interface Listener {
        void onDetermineOutputFormat();
    }
}
