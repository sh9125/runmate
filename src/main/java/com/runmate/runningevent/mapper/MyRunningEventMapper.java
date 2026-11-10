package com.runmate.runningevent.mapper;

import com.runmate.runningevent.dto.response.MyRunningEventApplicationStatus;
import com.runmate.runningevent.dto.response.MyRunningEventEndReason;
import com.runmate.runningevent.dto.response.MyRunningEventResponseDto;
import com.runmate.runningevent.entity.RunningEvent;

public class MyRunningEventMapper {
    private MyRunningEventMapper() {
    }

    public static MyRunningEventResponseDto toParticipatingResponse(RunningEvent event) {
        return new MyRunningEventResponseDto(RunningEventMapper.toListResponse(event),
                MyRunningEventApplicationStatus.PARTICIPATING, null, null);
    }

    public static MyRunningEventResponseDto toWaitingResponse(RunningEvent event, long position) {
        return new MyRunningEventResponseDto(RunningEventMapper.toListResponse(event),
                MyRunningEventApplicationStatus.WAITING, null, position);
    }

    public static MyRunningEventResponseDto toEndedResponse(RunningEvent event, MyRunningEventEndReason reason) {
        return new MyRunningEventResponseDto(RunningEventMapper.toListResponse(event),
                MyRunningEventApplicationStatus.ENDED, reason, null);
    }
}
