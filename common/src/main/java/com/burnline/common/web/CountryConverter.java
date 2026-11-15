package com.burnline.common.web;

import com.burnline.common.domain.Country;
import com.burnline.common.error.ApiException;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** 경로의 {country} → Country. 모르는 나라면 명세의 COUNTRY_NOT_READY(404). */
@Component
public class CountryConverter implements Converter<String, Country> {

    @Override
    public Country convert(String source) {
        try {
            return Country.valueOf(source);
        } catch (IllegalArgumentException e) {
            throw ApiException.countryNotReady(source);
        }
    }
}
