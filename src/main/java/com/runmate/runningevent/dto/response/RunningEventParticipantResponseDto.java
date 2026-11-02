package com.runmate.runningevent.dto.response;

import com.runmate.runningevent.entity.RunningEventParticipantStatus;

import java.time.LocalDateTime;

public record RunningEventParticipantResponseDto (
        Long id,
        Long eventId,
        Long memberId,
        RunningEventParticipantStatus status,
        LocalDateTime createdAt
) {
}
