package com.burnline.common.error;

import org.springframework.http.HttpStatus;

/** 명세 1장 에러 코드. 응답 형식은 {"code", "message"}. */
public enum ErrorCode {
    // 명세에 있는 코드
    REGION_NOT_FOUND(HttpStatus.NOT_FOUND),
    MONTH_OUT_OF_RANGE(HttpStatus.BAD_REQUEST),
    COUNTRY_NOT_READY(HttpStatus.NOT_FOUND),

    // 명세 밖 공통 코드 (프론트와 합의 필요)
    INVALID_PARAM(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    NOT_IMPLEMENTED(HttpStatus.NOT_IMPLEMENTED),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
