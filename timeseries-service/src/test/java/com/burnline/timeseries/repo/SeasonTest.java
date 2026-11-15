package com.burnline.timeseries.repo;

import com.burnline.common.domain.Country;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 올해 누적(ytd)의 시즌 계산과 퍼센타일. DB 없이 돈다 */
class SeasonTest {

    @Test
    void northernSeasonStartsInJanuary() {
        assertEquals("2026-01", TimeseriesRepository.seasonStartMonth(Country.KOR, "2026-03"));
        assertEquals(List.of(1, 2, 3), TimeseriesRepository.seasonMonths(Country.KOR, "2026-03"));
        assertEquals(12, TimeseriesRepository.seasonMonths(Country.CAL, "2025-12").size());
    }

    @Test
    void southernSeasonStartsInJuly() {
        assertEquals("2025-07", TimeseriesRepository.seasonStartMonth(Country.CHL, "2026-02"));
        assertEquals("2026-07", TimeseriesRepository.seasonStartMonth(Country.NSW, "2026-07"));
        assertEquals(List.of(7, 8, 9, 10, 11, 12, 1, 2), TimeseriesRepository.seasonMonths(Country.CHL, "2026-02"));
        assertEquals(List.of(7), TimeseriesRepository.seasonMonths(Country.NSW, "2026-07"));
        assertEquals(12, TimeseriesRepository.seasonMonths(Country.NSW, "2026-06").size());
    }

    @Test
    void percentileMatchesNumpyLinear() {
        List<Double> xs = List.of(1.0, 2.0, 3.0, 4.0, 10.0);
        assertEquals(3.0, TimeseriesRepository.percentile(xs, 50), 1e-9);
        assertEquals(1.6, TimeseriesRepository.percentile(xs, 15), 1e-9);   // numpy.percentile([1,2,3,4,10], 15) = 1.6
        assertEquals(6.4, TimeseriesRepository.percentile(xs, 85), 1e-9);   // 위치 3.4: 4 + 0.4·(10 − 4)
    }
}
