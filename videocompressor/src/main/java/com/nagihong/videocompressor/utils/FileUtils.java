package com.nagihong.videocompressor.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

/**
 * Created by channagihong on 7/11/17
 */

public class FileUtils {

    private static boolean isSpace(final String s) {
        if (s == null)
            return true;
        for (int i = 0, len = s.length(); i < len; ++i) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static File getFileByPath(final String filePath) {
        return isSpace(filePath) ? null : new File(filePath);
    }

    public static boolean createOrExistsDir(final File file) {
        return file != null && (file.exists() ? file.isDirectory() : file.mkdirs());
    }

    public static boolean copyFile(final String srcFilePath, final String destFilePath) {
        File srcFile = getFileByPath(srcFilePath);
        File destFile = getFileByPath(destFilePath);
        if (srcFile == null || destFile == null)
            return false;
        // 源文件不存在或者不是文件则返回false
        if (!srcFile.exists() || !srcFile.isFile())
            return false;
        // 目标文件存在且是文件则返回false
        if (destFile.exists() && destFile.isFile())
            return false;
        // 目标目录不存在返回false
        if (!createOrExistsDir(destFile.getParentFile()))
            return false;
        try {
            return FileIOUtils.writeFileFromIS(destFile, new FileInputStream(srcFile));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return false;
        }
    }
}
