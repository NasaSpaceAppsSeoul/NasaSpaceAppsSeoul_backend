package com.burnline.common.client;

import com.burnline.common.error.ApiException;
import org.springframework.http.HttpStatusCode;

/** 내부 호출 실패. status·code가 있으면 상대 서비스가 정상적으로 돌려준 에러다. */
public class UpstreamException extends RuntimeException {

    private final String target;
    private final Integer status;
    private final String code;

    public UpstreamException(String target, String message, Integer status, String code, Throwable cause) {
        super(message, cause);
        this.target = target;
        this.status = status;
        this.code = code;
    }

    public String target() {
        return target;
    }

    public Integer status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 404 같은 상대의 4xx 에러는 그대로, 나머지는 503 UPSTREAM_UNAVAILABLE */
    public ApiException toApiException() {
        if (status != null && status >= 400 && status < 500 && code != null) {
            return new ApiException(code, HttpStatusCode.valueOf(status), getMessage());
        }
        return ApiException.upstreamUnavailable(target);
    }
}
