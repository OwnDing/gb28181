package com.ownding.video.common;

public class ApiException extends RuntimeException {
    private final int status;
    private final int code;

    public ApiException(int status, int code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException(int status, String message) {
        this(status, status, message);
    }

    public int getStatus() {
        return status;
    }

    public int getCode() {
        return code;
    }
}
