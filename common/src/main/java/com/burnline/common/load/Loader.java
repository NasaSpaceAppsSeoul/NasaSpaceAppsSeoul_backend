package com.burnline.common.load;

import com.burnline.common.domain.Country;

/** 서비스마다 하나. 자기 스키마만 채운다. */
public interface Loader {

    /** 이 나라가 이미 적재됐는가 (data_version 행이 있는가) */
    boolean loaded(Country country);

    /** 원천 파일 → 자기 스키마. 한 트랜잭션으로 나라 단위 교체. */
    void load(Country country);
}
