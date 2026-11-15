package com.burnline.common.error;

import org.springframework.http.HttpStatusCode;

/** 컨트롤러·서비스에서 던지면 GlobalExceptionHandler가 {"code", "message"}로 바꾼다. */
public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatusCode status;

    public ApiException(ErrorCode code, String message) {
        this(code.name(), code.status(), message);
    }

    /** 다른 서비스가 준 에러 코드를 그대로 넘길 때 */
    public ApiException(String code, HttpStatusCode status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatusCode status() {
        return status;
    }

    public static ApiException regionNotFound(String country, String regionId) {
        return new ApiException(ErrorCode.REGION_NOT_FOUND, "region '%s' not found in %s".formatted(regionId, country));
    }

    public static ApiException monthOutOfRange(String month, String first, String last) {
        return new ApiException(ErrorCode.MONTH_OUT_OF_RANGE, "month '%s' is outside %s..%s".formatted(month, first, last));
    }

    public static ApiException countryNotReady(String country) {
        return new ApiException(ErrorCode.COUNTRY_NOT_READY, "country '%s' is not available".formatted(country));
    }

    public static ApiException invalidParam(String message) {
        return new ApiException(ErrorCode.INVALID_PARAM, message);
    }

    public static ApiException notImplemented(String what) {
        return new ApiException(ErrorCode.NOT_IMPLEMENTED, what + " is not implemented yet");
    }

    public static ApiException upstreamUnavailable(String target) {
        return new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, target + " service is unavailable");
    }
}
