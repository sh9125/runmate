package com.runmate.runningevent.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(oneOf = {RunningEventParticipantResponseDto.class, RunningEventWaitlistResponseDto.class})
public sealed interface RunningEventApplicationResponse
        permits RunningEventParticipantResponseDto, RunningEventWaitlistResponseDto {
    Long id();
}
