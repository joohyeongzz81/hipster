package com.hipster.rating.dto;

import com.hipster.rating.validation.ScoreValidator;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record CreateRatingRequest(
                @NotNull(message = "점수는 필수입니다.")
                @DecimalMin(value = "0.5", message = "최소 점수는 0.5입니다.")
                @DecimalMax(value = "5.0", message = "최대 점수는 5.0입니다.")
                @ScoreValidator(message = "점수는 0.5 단위여야 합니다.")
                Double score
                ) {
}
