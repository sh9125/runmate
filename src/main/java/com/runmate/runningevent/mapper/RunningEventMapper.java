package com.runmate.runningevent.mapper;

import com.runmate.member.entity.Member;
import com.runmate.runningevent.dto.request.RunningEventCreateRequestDto;
import com.runmate.runningevent.dto.response.RunningEventListResponseDto;
import com.runmate.runningevent.dto.response.RunningEventResponseDto;
import com.runmate.runningevent.dto.response.MyRunningEventApplicationStatus;
import com.runmate.runningevent.dto.response.MyRunningEventEndReason;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventStatus;

import java.util.List;

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

    // 생성·수정·개최 취소는 생성자 본인이 호출하므로 생성자의 현재 상태를 반환한다.
    public static RunningEventResponseDto toCreatorResponse(RunningEvent event, List<String> participantNicknames) {
        boolean canceled = event.getStatus() == RunningEventStatus.CANCELED;
        return toResponse(event, true,
                canceled ? MyRunningEventApplicationStatus.ENDED : MyRunningEventApplicationStatus.PARTICIPATING,
                canceled ? MyRunningEventEndReason.EVENT_CANCELED : null, null, participantNicknames);
    }

    public static RunningEventResponseDto toResponse(RunningEvent runningEvent, boolean isCreator,
                                                     MyRunningEventApplicationStatus applicationStatus,
                                                     MyRunningEventEndReason endReason, Long waitlistPosition,
                                                     List<String> participantNicknames) {
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
                runningEvent.getUpdatedAt(),
                isCreator, applicationStatus, endReason, waitlistPosition, participantNicknames);
    }

    public static RunningEventListResponseDto toListResponse(RunningEvent runningEvent) {
        return new RunningEventListResponseDto(
                runningEvent.getId(),
                runningEvent.getTitle(),
                runningEvent.getRegion(),
                runningEvent.getRunningPlace(),
                runningEvent.getStartAt(),
                runningEvent.getPreferredPaceSeconds(),
                runningEvent.getTargetDistanceKm(),
                runningEvent.getCapacity(),
                runningEvent.getCurrentParticipantCount(),
                runningEvent.getStatus(),
                runningEvent.getCreator().getNickname()
        );
    }
}
