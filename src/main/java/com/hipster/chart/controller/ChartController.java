package com.hipster.chart.controller;

import com.hipster.chart.dto.ChartFilterRequest;
import com.hipster.chart.dto.TopChartResponse;
import com.hipster.chart.service.ChartService;
import com.hipster.global.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/charts")
@RequiredArgsConstructor
public class ChartController {

    private final ChartService chartService;

    @GetMapping("/top")
    public ResponseEntity<ApiResponse<TopChartResponse>> getTopChart(
            @RequestParam(defaultValue = "100") final Integer limit,
            @ModelAttribute final ChartFilterRequest filter) {
        final TopChartResponse response = chartService.getTopChart(limit, filter);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
