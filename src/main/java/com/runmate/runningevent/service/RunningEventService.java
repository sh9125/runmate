package com.runmate.runningevent.service;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import com.runmate.runningevent.dto.request.RunningEventCreateRequestDto;
import com.runmate.runningevent.dto.response.RunningEventListResponseDto;
import com.runmate.runningevent.dto.response.RunningEventResponseDto;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventParticipant;
import com.runmate.runningevent.mapper.RunningEventMapper;
import com.runmate.runningevent.repository.RunningEventParticipantRepository;
import com.runmate.runningevent.repository.RunningEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class RunningEventService {
    private final RunningEventRepository runningEventRepository;
    private final MemberRepository memberRepository;
    private final RunningEventParticipantRepository runningEventParticipantRepository;

    @Transactional
    public RunningEventResponseDto createRunningEvent(RunningEventCreateRequestDto dto, String creatorEmail) {
        Member creator = memberRepository.findByEmail(creatorEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        RunningEvent runningEvent = RunningEventMapper.toEntity(dto, creator);
        RunningEvent savedEvent = runningEventRepository.save(runningEvent);
        RunningEventParticipant runningEventParticipant = RunningEventParticipant.builder()
                .runningEvent(savedEvent)
                .member(creator)
                .build();
        runningEventParticipantRepository.save(runningEventParticipant);
        return RunningEventMapper.toResponse(savedEvent);
    }

    @Transactional(readOnly = true)
    public Page<RunningEventListResponseDto> getRunningEvents(Pageable pageable) {
        return runningEventRepository.findAll(pageable)
                .map(RunningEventMapper::toListResponse);
    }

    @Transactional(readOnly = true)
    public RunningEventResponseDto getRunningEvent(Long eventId) {
        RunningEvent runningEvent = runningEventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUNNING_EVENT_NOT_FOUND));
        return RunningEventMapper.toResponse(runningEvent);
    }

}
