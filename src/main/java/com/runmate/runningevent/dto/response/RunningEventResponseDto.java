package com.runmate.runningevent.dto.response;

import com.runmate.runningevent.entity.RunningEventStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record RunningEventResponseDto(
        Long id,
        String title,
        String description,
        String region,
        String runningPlace,
        String meetingPlace,
        LocalDateTime startAt,
        Integer preferredPaceSeconds,
        BigDecimal targetDistanceKm,
        Integer capacity,
        Integer currentParticipantCount,
        RunningEventStatus status,
        Long creatorId,
        String creatorNickname,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean isCreator,
        MyRunningEventApplicationStatus applicationStatus,
        MyRunningEventEndReason endReason,
        Long waitlistPosition,
        List<String> participantNicknames
) {
}
