package com.hipster.chart.controller;

import com.hipster.chart.dto.request.ChartFilterRequest;
import com.hipster.chart.dto.response.TopChartResponse;
import com.hipster.chart.service.ChartService;
import com.hipster.global.dto.response.ApiResponse;
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

    @GetMapping
    public ResponseEntity<ApiResponse<TopChartResponse>> getCharts(
            @ModelAttribute final ChartFilterRequest filter,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "20") final int size) {
        final TopChartResponse response = chartService.getCharts(filter, page, size);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
