
package com.nagihong.videocompressor.transcoder;

public class InvalidOutputFormatException extends RuntimeException {
    public InvalidOutputFormatException(String detailMessage) {
        super(detailMessage);
    }
}
