package com.runmate.runningevent.mapper;

import com.runmate.member.entity.Member;
import com.runmate.runningevent.dto.request.RunningEventCreateRequestDto;
import com.runmate.runningevent.dto.response.RunningEventResponseDto;
import com.runmate.runningevent.entity.RunningEvent;

public class RunningEventMapper {

    private RunningEventMapper() {

    }

    public static RunningEvent toEntity(RunningEventCreateRequestDto dto, Member creator) {
        return RunningEvent.builder()
                .title(dto.getTitle())
                .description(dto.getDescription())
                .region(dto.getRegion())
                .runningPlace(dto.getRunningPlace())
                .meetingPlace(dto.getMeetingPlace())
                .startAt(dto.getStartAt())
                .preferredPaceSeconds(dto.getPreferredPaceSeconds())
                .targetDistanceKm(dto.getTargetDistanceKm())
                .capacity(dto.getCapacity())
                .creator(creator)
                .build();
    }

    public static RunningEventResponseDto toResponse(RunningEvent runningEvent) {
        return new RunningEventResponseDto(
                runningEvent.getId(),
                runningEvent.getTitle(),
                runningEvent.getDescription(),
                runningEvent.getRegion(),
                runningEvent.getRunningPlace(),
                runningEvent.getMeetingPlace(),
                runningEvent.getStartAt(),
                runningEvent.getPreferredPaceSeconds(),
                runningEvent.getTargetDistanceKm(),
                runningEvent.getCapacity(),
                runningEvent.getCurrentParticipantCount(),
                runningEvent.getStatus(),
                runningEvent.getCreator().getId(),
                runningEvent.getCreator().getNickname(),
                runningEvent.getCreatedAt(),
                runningEvent.getUpdatedAt());
    }
}
