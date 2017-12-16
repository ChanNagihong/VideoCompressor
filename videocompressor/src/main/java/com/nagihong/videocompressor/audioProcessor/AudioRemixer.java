package com.nagihong.videocompressor.audioProcessor;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * 假如声音的值范围为(0, 255)
 * 128(midpoint)代表没有声音
 * 两个声音源A和B，假设合成结果为Z
 * 当A和B都小于128的时候，
 * Z = (AB) / 128;
 * 否则
 * Z = 2(A + B) - (AB)/128 - 256;
 */
public interface AudioRemixer {
    void remix(final ShortBuffer inSBuff, final ShortBuffer outSBuff);

    void remix(final ByteBuffer inSBuff, final ByteBuffer outSBuff);

    AudioRemixer DOWNMIX = new AudioRemixer() {
        private static final int SIGNED_SHORT_LIMIT = 32768;
        private static final int UNSIGNED_SHORT_MAX = 65535;

        @Override
        public void remix(final ShortBuffer inSBuff, final ShortBuffer outSBuff) {
            // Down-mix stereo to mono
            // Viktor Toth's algorithm -
            // See: http://www.vttoth.com/CMS/index.php/technical-notes/68
            //      http://stackoverflow.com/a/25102339
            final int inRemaining = inSBuff.remaining() / 2;
            final int outSpace = outSBuff.remaining();

            final int samplesToBeProcessed = Math.min(inRemaining, outSpace);
            for (int i = 0; i < samplesToBeProcessed; ++i) {
                // Convert to unsigned
                final int a = inSBuff.get() + SIGNED_SHORT_LIMIT;
                final int b = inSBuff.get() + SIGNED_SHORT_LIMIT;
                int m;
                // Pick the equation
                if ((a < SIGNED_SHORT_LIMIT) || (b < SIGNED_SHORT_LIMIT)) {
                    // Viktor's first equation when both sources are "quiet"
                    // (i.e. less than middle of the dynamic range)
                    m = a * b / SIGNED_SHORT_LIMIT;
                } else {
                    // Viktor's second equation when one or both sources are loud
                    m = 2 * (a + b) - (a * b) / SIGNED_SHORT_LIMIT - UNSIGNED_SHORT_MAX;
                }
                // Convert output back to signed short
                if (m == UNSIGNED_SHORT_MAX + 1) m = UNSIGNED_SHORT_MAX;
                outSBuff.put((short) (m - SIGNED_SHORT_LIMIT));
            }
        }

        @Override
        public void remix(ByteBuffer inSBuff, ByteBuffer outSBuff) {
            remix(inSBuff.asShortBuffer(), outSBuff.asShortBuffer());
        }
    };

    AudioRemixer UPMIX = new AudioRemixer() {
        @Override
        public void remix(final ShortBuffer inSBuff, final ShortBuffer outSBuff) {
            // Up-mix mono to stereo
            final int inRemaining = inSBuff.remaining();
            final int outSpace = outSBuff.remaining() / 2;

            final int samplesToBeProcessed = Math.min(inRemaining, outSpace);
            for (int i = 0; i < samplesToBeProcessed; ++i) {
                final short inSample = inSBuff.get();
                //upmix就是同样的声音数据，放两份进去,等于效果*2
                outSBuff.put(inSample);
                outSBuff.put(inSample);
            }
        }

        @Override
        public void remix(ByteBuffer inSBuff, ByteBuffer outSBuff) {
            remix(inSBuff.asShortBuffer(), outSBuff.asShortBuffer());
        }
    };

    AudioRemixer PASSTHROUGH = new AudioRemixer() {
        @Override
        public void remix(final ShortBuffer inSBuff, final ShortBuffer outSBuff) {
            // Passthrough
            outSBuff.put(inSBuff);
        }

        @Override
        public void remix(ByteBuffer inSBuff, ByteBuffer outSBuff) {
            remix(inSBuff.asShortBuffer(), outSBuff.asShortBuffer());
        }
    };
}
