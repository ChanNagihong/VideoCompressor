package com.nagihong.videocompressor.utils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Created by channagihong on 8/9/17
 */

public class FileIOUtils {

    private static int bufferSize = 8192;

    private static boolean createOrExistsFile(final File file) {
        if (file == null)
            return false;
        if (file.exists())
            return file.isFile();
        if (!FileUtils.createOrExistsDir(file.getParentFile()))
            return false;
        try {
            return file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean writeFileFromIS(final File file, final InputStream is) {
        if (!createOrExistsFile(file) || is == null)
            return false;
        OutputStream os = null;
        try {
            os = new BufferedOutputStream(new FileOutputStream(file, false));
            byte data[] = new byte[bufferSize];
            int len;
            while ((len = is.read(data, 0, bufferSize)) != -1) {
                os.write(data, 0, len);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            if (null != is) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (null != os) {
                try {
                    os.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

}
