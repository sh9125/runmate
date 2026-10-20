package com.runmate.runningevent.dto.response;

import java.time.LocalDateTime;

public record RunningEventParticipantResponseDto (
        Long id,
        Long eventId,
        Long memberId,
        LocalDateTime createdAt
) {
}
