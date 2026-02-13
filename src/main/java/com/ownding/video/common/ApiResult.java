package com.ownding.video.common;

public record ApiResult<T>(int code, String message, T data) {
    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(0, "ok", data);
    }

    public static <T> ApiResult<T> success(String message, T data) {
        return new ApiResult<>(0, message, data);
    }

    public static ApiResult<Void> successMessage(String message) {
        return new ApiResult<>(0, message, null);
    }

    public static <T> ApiResult<T> fail(int code, String message) {
        return new ApiResult<>(code, message, null);
    }
}
