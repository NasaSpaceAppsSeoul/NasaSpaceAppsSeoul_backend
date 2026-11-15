package com.burnline.timeseries.api;

import com.burnline.common.domain.Country;
import com.burnline.common.web.Params;
import com.burnline.timeseries.judge.JudgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final JudgeService judge;

    /** insight가 머리 카드·ref·순위에 쓴다. 응답 = 명세 7-5. */
    @GetMapping("/judge")
    public Map<String, Object> judge(@RequestParam Country country,
                                     @RequestParam("region_id") String regionId,
                                     @RequestParam String month) {
        return judge.judge(country, regionId, Params.month(month, "month"));
    }
}
