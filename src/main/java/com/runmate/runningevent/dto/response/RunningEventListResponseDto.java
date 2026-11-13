package com.runmate.runningevent.dto.response;

import com.runmate.runningevent.entity.RunningEventStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RunningEventListResponseDto(
        Long id,
        String title,
        String region,
        String runningPlace,
        LocalDateTime startAt,
        Integer preferredPaceSeconds,
        BigDecimal targetDistanceKm,
        Integer capacity,
        Integer currentParticipantCount,
        RunningEventStatus status,
        String creatorNickname
) {
    public RunningEventListResponseDto withRecruitmentStatusAt(LocalDateTime now) {
        if (status != RunningEventStatus.RECRUITING || now.isBefore(startAt)) {
            return this;
        }
        return new RunningEventListResponseDto(id, title, region, runningPlace, startAt,
                preferredPaceSeconds, targetDistanceKm, capacity, currentParticipantCount,
                RunningEventStatus.CLOSED, creatorNickname);
    }
}
