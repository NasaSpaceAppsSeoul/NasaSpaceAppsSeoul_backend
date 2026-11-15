package com.burnline.timeseries.service;

import com.burnline.common.domain.Country;
import com.burnline.common.error.ApiException;
import com.burnline.common.web.Params;
import com.burnline.timeseries.repo.TimeseriesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 월 파라미터 검증: 형식(INVALID_PARAM) + 자료 범위(MONTH_OUT_OF_RANGE) */
@Component
@RequiredArgsConstructor
public class Months {

    private final TimeseriesRepository repo;

    public String check(Country country, String month) {
        return check(country, month, "month");
    }

    public String check(Country country, String month, String name) {
        Params.month(month, name);
        if (month == null) {
            return null;
        }
        String[] range = repo.monthRange(country).orElseThrow(() -> ApiException.countryNotReady(country.name()));
        if (month.compareTo(range[0]) < 0 || month.compareTo(range[1]) > 0) {
            throw ApiException.monthOutOfRange(month, range[0], range[1]);
        }
        return month;
    }
}
