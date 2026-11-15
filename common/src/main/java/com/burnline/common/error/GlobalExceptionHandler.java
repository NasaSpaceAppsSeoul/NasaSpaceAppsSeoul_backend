package com.burnline.common.error;

import com.burnline.common.client.UpstreamException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 모든 에러를 명세 형식 {"code", "message"}로. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage()));
    }

    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<ErrorResponse> upstream(UpstreamException e) {
        return api(e.toApiException());
    }

    /** {country} 변환 실패 등. 변환기가 던진 ApiException(COUNTRY_NOT_READY)이 원인이면 그걸 쓴다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ApiException api) {
                return api(api);
            }
        }
        return api(ApiException.invalidParam("%s: invalid value '%s'".formatted(e.getName(), e.getValue())));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> missing(MissingServletRequestParameterException e) {
        return api(ApiException.invalidParam(e.getParameterName() + ": required"));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> validation(HandlerMethodValidationException e) {
        return api(ApiException.invalidParam(e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NoResourceFoundException e) {
        return api(new ApiException(ErrorCode.NOT_FOUND, "no route for " + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unhandled(Exception e) {
        log.error("unhandled error", e);
        return api(new ApiException(ErrorCode.INTERNAL, "internal server error"));
    }
}
