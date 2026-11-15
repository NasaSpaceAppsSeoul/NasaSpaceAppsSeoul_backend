package com.burnline.common.web;

import com.burnline.common.error.ApiException;

import java.util.regex.Pattern;

/** 명세 1장 날짜 형식: 월 YYYY-MM, 일 YYYY-MM-DD */
public final class Params {

    private static final Pattern MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$");

    private Params() {
    }

    /** null이면 null 그대로 (선택 파라미터) */
    public static String month(String value, String name) {
        if (value != null && !MONTH.matcher(value).matches()) {
            throw ApiException.invalidParam("%s: expected YYYY-MM, got '%s'".formatted(name, value));
        }
        return value;
    }

    /**
     * 월 범위 from·to: 형식(YYYY-MM)과 순서를 함께 검사한다. from이 to보다 뒤면 어떤 달이든 INVALID_PARAM (400).
     * 같은 달은 한 달 범위로 허용, 한쪽만 있으면 순서 검사는 하지 않는다.
     * A /series와 B /events 같은 범위 파라미터가 모두 이것을 쓰면 규칙이 한 곳에 남는다.
     */
    public static void monthRange(String from, String to) {
        month(from, "from");
        month(to, "to");
        if (from != null && to != null && from.compareTo(to) > 0) {
            throw ApiException.invalidParam("from must not be after to: from=%s, to=%s".formatted(from, to));
        }
    }

    public static String date(String value, String name) {
        if (value != null && !DATE.matcher(value).matches()) {
            throw ApiException.invalidParam("%s: expected YYYY-MM-DD, got '%s'".formatted(name, value));
        }
        return value;
    }
}
