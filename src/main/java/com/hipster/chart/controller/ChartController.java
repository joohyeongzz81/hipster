package com.hipster.chart.controller;

import com.hipster.chart.dto.TopChartResponse;
import com.hipster.chart.service.ChartService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/charts")
@RequiredArgsConstructor
public class ChartController {

    private final ChartService chartService;

    @GetMapping("/top")
    public ResponseEntity<TopChartResponse> getTopChart(
            @RequestParam(defaultValue = "100") Integer limit
    ) {
        if (limit < 10 || limit > 1000) {
            throw new IllegalArgumentException("Limit must be between 10 and 1000");
        }

        TopChartResponse response = chartService.getTopChart(limit);
        return ResponseEntity.ok(response);
    }
}
