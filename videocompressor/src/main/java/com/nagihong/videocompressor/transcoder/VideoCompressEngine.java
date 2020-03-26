package com.nagihong.videocompressor.transcoder;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.nagihong.videocompressor.strategies.MediaFormatStrategy;
import com.nagihong.videocompressor.trackTranscoder.AudioTrackTranscoder;
import com.nagihong.videocompressor.trackTranscoder.PassThroughTrackTranscoder;
import com.nagihong.videocompressor.trackTranscoder.TrackTranscoder;
import com.nagihong.videocompressor.trackTranscoder.VideoTrackTranscoder;
import com.nagihong.videocompressor.utils.FileUtils;
import com.nagihong.videocompressor.utils.MediaExtractorUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;

// TODO: treat encrypted data
public class VideoCompressEngine {
    private static final String TAG = "VideoCompressEngine";

    //progress related
    private static final double PROGRESS_UNKNOWN = -1.0;
    private static final long PROGRESS_INTERVAL_STEPS = 10;
    private volatile double progress;
    private long durationUS;

    //parameters
    private FileDescriptor inputFileDescriptor;
    private String inputPath;
    private String outputPath;

    private static final long SLEEP_TO_WAIT_TRACK_TRANSCODERS = 10;
    private TrackTranscoder videoTrackTranscoder;
    private TrackTranscoder audioTrackTranscoder;
    private MediaExtractor extractor;
    private MediaMuxer muxer;
    private ProgressCallback progressCallback;

    /**
     * Run video transcoding. Blocks current thread.
     * Audio data will not be transcoded; original stream will be wrote to output file.
     * <p>
     * #BRIEF
     * init(MediaExtractor, MediaMuxer);
     * setupMetaData() -> MediaMetaDataRetriever -> get video rotation and duration;
     * setupTrackTranscoders() -> {
     * getFromMediaExtractor;
     * validMediaFormat;
     * init(QueuedMuxer);
     * setup(VideoTrackTranscoder, AudioTrackTranscoder);
     * MediaExtractor.selectVideoTrack, MediaExtractor.selectAudioTrack;
     * }
     * runPipelines() -> VideoTrackTranscoder.stepPipeline, AudioTrackTranscoder.stepPipeline;
     *
     * @param formatStrategy Output format strategy.
     * @throws IOException                  when input or output file could not be opened.
     * @throws InvalidOutputFormatException when output format is not supported.
     * @throws InterruptedException         when cancel to transcode.
     */
    public void transcodeVideo(Context context, String inputPath, String outputPath, MediaFormatStrategy formatStrategy) throws IOException, InterruptedException {
        setup(context, inputPath, outputPath);
        //start transcoding
        extractor = new MediaExtractor(); // NOTE: use single extractor to keep from running out audio track fast.
        extractor.setDataSource(inputFileDescriptor);
        muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        readMetaData();
        if (setupTrackTranscoders(formatStrategy)) {
            runPipelines();
        }
        muxer.stop();
        release();
    }

    private void setup(Context context, String inputPath, String outputPath) {
        this.inputPath = inputPath;
        this.outputPath = outputPath;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = Uri.parse(new File(inputPath).toURI().toString());
            try {
                inputFileDescriptor = context.getContentResolver().openFile(uri, "r", null).getFileDescriptor();
            } catch (FileNotFoundException | NullPointerException e) {
                e.printStackTrace();
            }
        } else {
            //checkers
            if (null == inputPath) {
                throw new NullPointerException("Input path cannot be null.");
            }
            if (null == outputPath) {
                throw new NullPointerException("Output path cannot be null.");
            }

            try {
                inputFileDescriptor = new FileInputStream(inputPath).getFD();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (inputFileDescriptor == null) {
            throw new IllegalStateException("Data source is not set.");
        }

    }

    private void readMetaData() throws IOException {
        MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
        mediaMetadataRetriever.setDataSource(inputFileDescriptor);

        String rotationString = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        try {
            muxer.setOrientationHint(Integer.parseInt(rotationString));
        } catch (NumberFormatException e) {
            // skip
        }

        try {
            durationUS = Long.parseLong(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000;
        } catch (NumberFormatException e) {
            durationUS = -1;
        }
        Log.d(TAG, "Duration (us): " + durationUS);
        mediaMetadataRetriever.release();
    }

    /**
     * @return whether keep going compressing
     */
    private boolean setupTrackTranscoders(MediaFormatStrategy formatStrategy) {
        QueuedMuxer queuedMuxer = new QueuedMuxer(muxer, () -> {
            MediaFormatValidator.validateVideoOutputFormat(videoTrackTranscoder.getDeterminedFormat());
            MediaFormatValidator.validateAudioOutputFormat(audioTrackTranscoder.getDeterminedFormat());
        });

        //read
        MediaExtractorUtils.TrackResult trackResult = MediaExtractorUtils.getFirstVideoAndAudioTrack(extractor);
        MediaFormat videoOutputFormat = formatStrategy.createVideoOutputFormat(trackResult.mVideoTrackFormat);
        MediaFormat audioOutputFormat = formatStrategy.createAudioOutputFormat(trackResult.mAudioTrackFormat);
        if (videoOutputFormat == null && audioOutputFormat == null) {
            //our project want the output file when no need to transcode
            FileUtils.copyFile(inputPath, outputPath);
            return false;
        }

        //deal output format
        if (videoOutputFormat == null) {
            videoTrackTranscoder = new PassThroughTrackTranscoder(extractor, trackResult.mVideoTrackIndex, queuedMuxer, QueuedMuxer.SampleType.VIDEO);
        } else {
            videoTrackTranscoder = new VideoTrackTranscoder(extractor, trackResult.mVideoTrackIndex, videoOutputFormat, queuedMuxer);
        }
        videoTrackTranscoder.setup();
        if (audioOutputFormat == null) {
            audioTrackTranscoder = new PassThroughTrackTranscoder(extractor, trackResult.mAudioTrackIndex, queuedMuxer, QueuedMuxer.SampleType.AUDIO);
        } else {
            audioTrackTranscoder = new AudioTrackTranscoder(extractor, trackResult.mAudioTrackIndex, audioOutputFormat, queuedMuxer);
        }
        audioTrackTranscoder.setup();

        //select source track
        extractor.selectTrack(trackResult.mVideoTrackIndex);
        extractor.selectTrack(trackResult.mAudioTrackIndex);
        return true;
    }

    private void runPipelines() throws InterruptedException {
        long loopCount = 0;
        while (!isFinished()) {
            boolean stepped = stepPipeline();
            loopCount++;

            calculateProgress(loopCount);
            //sleep to retry again
            if (!stepped) {
                Thread.sleep(SLEEP_TO_WAIT_TRACK_TRANSCODERS);
            }
        }
    }

    //========================= getters and setters ========================================================
    public ProgressCallback getProgressCallback() {
        return progressCallback;
    }

    public void setProgressCallback(ProgressCallback progressCallback) {
        this.progressCallback = progressCallback;
    }

    private boolean isFinished() {
        return videoTrackTranscoder.isFinished() && audioTrackTranscoder.isFinished();
    }

    //========================= 其他业务 ========================================================
    private boolean stepPipeline() {
        return videoTrackTranscoder.stepPipeline() || audioTrackTranscoder.stepPipeline();
    }

    private void calculateProgress(long loopCount) {
        if (null != progressCallback && durationUS > 0 && loopCount % PROGRESS_INTERVAL_STEPS == 0) {
            if (durationUS <= 0) {
                this.progress = PROGRESS_UNKNOWN;
            } else {
                double videoProgress = videoTrackTranscoder.isFinished() ? 1.0 : Math.min(1.0, (double) videoTrackTranscoder.getWrittenPresentationTimeUs() / durationUS);
                double audioProgress = audioTrackTranscoder.isFinished() ? 1.0 : Math.min(1.0, (double) audioTrackTranscoder.getWrittenPresentationTimeUs() / durationUS);
                this.progress = (videoProgress + audioProgress) / 2.0;
            }
            progressCallback.onProgress(progress);
        }
    }

    private void release() {
        try {
            if (videoTrackTranscoder != null) {
                videoTrackTranscoder.release();
                videoTrackTranscoder = null;
            }
            if (audioTrackTranscoder != null) {
                audioTrackTranscoder.release();
                audioTrackTranscoder = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
        } catch (RuntimeException e) {
            // Too fatal to make alive the app, because it may leak native resources.
            //noinspection ThrowFromFinallyBlock
            throw new Error("Could not shutdown extractor, codecs and muxer pipeline.", e);
        }
        try {
            if (muxer != null) {
                muxer.release();
                muxer = null;
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to release muxer.", e);
        }
    }

    public interface ProgressCallback {
        /**
         * Called to notify progress. Same thread which initiated transcode is used.
         *
         * @param progress Progress in [0.0, 1.0] range, or negative value if progress is unknown.
         */
        void onProgress(double progress);
    }
}
