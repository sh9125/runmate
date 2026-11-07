package com.runmate.runningevent.dto.response;

import com.runmate.runningevent.entity.RunningEventWaitlistStatus;

import java.time.LocalDateTime;

public record RunningEventWaitlistResponseDto(
        Long id,
        Long eventId,
        Long memberId,
        RunningEventWaitlistStatus status,
        Long position,
        LocalDateTime requestedAt
) implements RunningEventApplicationResponse {
}
