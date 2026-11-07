package com.runmate.runningevent.mapper;

import com.runmate.runningevent.dto.response.RunningEventWaitlistResponseDto;
import com.runmate.runningevent.entity.RunningEventWaitlistEntry;

public class RunningEventWaitlistMapper {

    private RunningEventWaitlistMapper() {
    }

    public static RunningEventWaitlistResponseDto toResponse(RunningEventWaitlistEntry entry, Long position) {
        return new RunningEventWaitlistResponseDto(
                entry.getId(),
                entry.getRunningEvent().getId(),
                entry.getMember().getId(),
                entry.getStatus(),
                position,
                entry.getRequestedAt()
        );
    }
}
