package com.burnline.common.error;

/** 명세 7-12: { "code": "REGION_NOT_FOUND", "message": "region 'ABC' not found in KOR" } */
public record ErrorResponse(String code, String message) {
}
