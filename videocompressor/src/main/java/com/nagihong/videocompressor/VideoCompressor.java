package com.nagihong.videocompressor;

import com.nagihong.videocompressor.strategies.Android720pFormatStrategy;
import com.nagihong.videocompressor.strategies.MediaFormatStrategy;
import com.nagihong.videocompressor.transcoder.VideoCompressEngine;

import java.io.IOException;

public class VideoCompressor {

    public boolean compressVideo(String inputPath, String outPath) {
        return compressVideo(inputPath, outPath, new Android720pFormatStrategy(1280000, 128000, 1));
    }

    public boolean compressVideo(String inputPath, String outputPath, MediaFormatStrategy strategy) {
        VideoCompressEngine engine = new VideoCompressEngine();
        try {
            engine.transcodeVideo(inputPath, outputPath, strategy);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }
}
