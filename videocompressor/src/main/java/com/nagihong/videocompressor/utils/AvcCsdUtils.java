package com.nagihong.videocompressor.utils;

import android.media.MediaFormat;

import com.nagihong.videocompressor.strategies.MediaFormatExtraConstants;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 主要为了检查 MediaFormat.getByteBuffer("csd-0")的startCode后面一位byte是否在{103，39，71}其中一位，否则报错
 * 为什么一定要有这一位byte?
 */
public class AvcCsdUtils {
    // Refer: https://android.googlesource.com/platform/frameworks/av/+/lollipop-release/media/libstagefright/MediaCodec.cpp#2198
    // Refer: http://stackoverflow.com/a/2861340
    private static final byte[] AVC_START_CODE_3 = {0x00, 0x00, 0x01};
    private static final byte[] AVC_START_CODE_4 = {0x00, 0x00, 0x00, 0x01};
    // Refer: http://www.cardinalpeak.com/blog/the-h-264-sequence-parameter-set/
    private static final byte AVC_SPS_NAL = 103; // 0<<7 + 3<<5 + 7<<0
    // https://tools.ietf.org/html/rfc6184
    private static final byte AVC_SPS_NAL_2 = 39; // 0<<7 + 1<<5 + 7<<0
    private static final byte AVC_SPS_NAL_3 = 71; // 0<<7 + 2<<5 + 7<<0

    /**
     * @return ByteBuffer contains SPS without NAL header.
     */
    public static ByteBuffer getSpsBuffer(MediaFormat format) {
        ByteBuffer sourceBuffer = format.getByteBuffer(MediaFormatExtraConstants.KEY_AVC_SPS).asReadOnlyBuffer(); // might be direct buffer
        ByteBuffer prefixedSpsBuffer = ByteBuffer.allocate(sourceBuffer.limit()).order(sourceBuffer.order());
        prefixedSpsBuffer.put(sourceBuffer);
        prefixedSpsBuffer.flip();

        skipStartCode(prefixedSpsBuffer);

        byte spsNalData = prefixedSpsBuffer.get();
        if (spsNalData != AVC_SPS_NAL && spsNalData != AVC_SPS_NAL_2 && spsNalData != AVC_SPS_NAL_3) {
            throw new IllegalStateException("Got non SPS NAL data.");
        }

        return prefixedSpsBuffer.slice();
    }

    //与其说是skip，不如说是过滤startCode不满足条件的buffer
    private static void skipStartCode(ByteBuffer prefixedSpsBuffer) {
        byte[] prefix3 = new byte[3];
        prefixedSpsBuffer.get(prefix3);
        if (Arrays.equals(prefix3, AVC_START_CODE_3)) return;

        byte[] prefix4 = Arrays.copyOf(prefix3, 4);
        prefix4[3] = prefixedSpsBuffer.get();
        if (Arrays.equals(prefix4, AVC_START_CODE_4)) return;
        throw new IllegalStateException("AVC NAL start code does not found in csd.");
    }

    private AvcCsdUtils() {
        throw new RuntimeException();
    }
}
