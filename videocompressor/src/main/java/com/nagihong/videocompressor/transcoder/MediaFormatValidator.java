package com.nagihong.videocompressor.transcoder;

import android.media.MediaFormat;

import com.nagihong.videocompressor.strategies.MediaFormatExtraConstants;
import com.nagihong.videocompressor.utils.AvcCsdUtils;
import com.nagihong.videocompressor.utils.AvcSpsUtils;

import java.nio.ByteBuffer;

/*
    h.264 bitstreams
    const uint8_t sps[] =
        {0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x0a, 0xf8, 0x41, 0xa2};
        {0x00, 0x00, 0x00, 0x01} 是 startCode
        {0x67}是 sps nal
        {0x42} = 66 = {@link #PROFILE_IDC_BASELINE}

     Parameter Name	                        Type	    Value	    Comments
     forbidden_zero_bit	                    u(1)	    0	        Despite being forbidden, it must be set to 0!
     nal_ref_idc	                        u(2)	    3	        3 means it is “important” (this is an SPS)
     nal_unit_type	                        u(5)	    7	        Indicates this is a sequence parameter set
     profile_idc	                        u(8)	    66	        Baseline profile
     constraint_set0_flag	                u(1)	    0	        We’re not going to honor constraints
     constraint_set1_flag	                u(1)	    0	        We’re not going to honor constraints
     constraint_set2_flag	                u(1)	    0	        We’re not going to honor constraints
     constraint_set3_flag	                u(1)	    0	        We’re not going to honor constraints
     reserved_zero_4bits	                u(4)	    0	        Better set them to zero
     level_idc	                            u(8)	    10	        Level 1, sec A.3.1
     seq_parameter_set_id	                ue(v)	    0	        We’ll just use id 0.
     log2_max_frame_num_minus4	            ue(v)	    0	        Let’s have as few frame numbers as possible
     pic_order_cnt_type	                    ue(v)	    0	        Keep things simple
     log2_max_pic_order_cnt_lsb_minus4	    ue(v)	    0	        Fewer is better.
     num_ref_frames	                        ue(v)	    0	        We will only send I slices
     gaps_in_frame_num_value_allowed_flag	u(1)	    0	        We will have no gaps
     pic_width_in_mbs_minus_1	            ue(v)	    7	        SQCIF is 8 macroblocks wide
     pic_height_in_map_units_minus_1	    ue(v)	    5	        SQCIF is 6 macroblocks high
     frame_mbs_only_flag	                u(1)	    1	        We will not to field/frame encoding
     direct_8x8_inference_flag	            u(1)	    0	        Used for B slices. We will not send B slices
     frame_cropping_flag	                u(1)	    0	        We will not do frame cropping
     vui_prameters_present_flag	            u(1)	    0	        We will not send VUI data
     rbsp_stop_one_bit	                    u(1)	    1	        Stop bit. I missed this at first and it caused me much trouble.
 */
class MediaFormatValidator {
    // Refer: http://en.wikipedia.org/wiki/H.264/MPEG-4_AVC#Profiles
    private static final byte PROFILE_IDC_BASELINE = 66;
    private static final byte PROFILE_IDC_EXTENDED = 88;
    private static final byte PROFILE_IDC_MAIN = 77;
    private static final byte PROFILE_IDC_HIGH = 100;

    /**
     * 1、check mime type
     * 2、check sps buffer startcode and nal
     * 3、check PROFILE_IDC_BASELINE
     */
    public static void validateVideoOutputFormat(MediaFormat format) {
        String mime = format.getString(MediaFormat.KEY_MIME);
        // Refer: http://developer.android.com/guide/appendix/media-formats.html#core
        // Refer: http://en.wikipedia.org/wiki/MPEG-4_Part_14#Data_streams
        if (!MediaFormatExtraConstants.MIMETYPE_VIDEO_AVC.equals(mime)) {
            throw new InvalidOutputFormatException("Video codecs other than AVC is not supported, actual mime type: " + mime);
        }
        ByteBuffer spsBuffer = AvcCsdUtils.getSpsBuffer(format);
        byte profileIdc = AvcSpsUtils.getProfileIdc(spsBuffer);
        boolean baseline = profileIdc == PROFILE_IDC_BASELINE;
        boolean extended = profileIdc == PROFILE_IDC_EXTENDED;
        boolean main = profileIdc == PROFILE_IDC_MAIN;
        boolean high = profileIdc == PROFILE_IDC_HIGH;
        if (!baseline && !extended && !main && !high) {
            throw new InvalidOutputFormatException("Non AVC video profile is not supported by Android OS, actual profile_idc: " + profileIdc);
        }
    }

    /**
     * only check mime type
     */
    public static void validateAudioOutputFormat(MediaFormat format) {
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (!MediaFormatExtraConstants.MIMETYPE_AUDIO_AAC.equals(mime)) {
            throw new InvalidOutputFormatException("Audio codecs other than AAC is not supported, actual mime type: " + mime);
        }
    }
}
