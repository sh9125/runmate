package com.runmate.runningevent.dto.request;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class RunningEventCreateRequestDto {
    @NotBlank
    @Size(max = 255)
    private String title;

    @NotBlank
    @Size(max = 255)
    private String description;

    @NotBlank
    @Size(max = 255)
    private String region;

    @NotBlank
    @Size(max = 255)
    private String runningPlace;

    @NotBlank
    @Size(max = 255)
    private String meetingPlace;

    @NotNull
    @Future
    private LocalDateTime startAt;

    @NotNull
    @Positive
    private Integer preferredPaceSeconds;

    @NotNull
    @DecimalMin(value = "0.1")
    private BigDecimal targetDistanceKm;

    @NotNull
    @Min(1)
    private Integer capacity;
}
