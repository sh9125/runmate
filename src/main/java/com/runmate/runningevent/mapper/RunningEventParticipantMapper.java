package com.runmate.runningevent.mapper;

import com.runmate.runningevent.dto.response.RunningEventParticipantResponseDto;
import com.runmate.runningevent.entity.RunningEventParticipant;

public class RunningEventParticipantMapper {

    private RunningEventParticipantMapper() {

    }

    public static RunningEventParticipantResponseDto toResponse(RunningEventParticipant runningEventParticipant) {
        return new RunningEventParticipantResponseDto(
                runningEventParticipant.getId(),
                runningEventParticipant.getRunningEvent().getId(),
                runningEventParticipant.getMember().getId(),
                runningEventParticipant.getCreatedAt()
        );
    }
}
