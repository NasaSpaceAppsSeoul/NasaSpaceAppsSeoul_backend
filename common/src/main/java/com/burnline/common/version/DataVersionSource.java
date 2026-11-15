package com.burnline.common.version;

import com.burnline.common.domain.Country;

import java.util.Optional;

/** Redis에 data_version이 없을 때 DB(ops.data_version)에서 읽는 쪽. DB 없는 서비스(insight)에는 없다. */
public interface DataVersionSource {
    Optional<String> find(Country country);
}
