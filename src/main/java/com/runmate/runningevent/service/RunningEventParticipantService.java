package com.runmate.runningevent.service;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import com.runmate.runningevent.dto.response.RunningEventParticipantResponseDto;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventParticipant;
import com.runmate.runningevent.mapper.RunningEventParticipantMapper;
import com.runmate.runningevent.repository.RunningEventParticipantRepository;
import com.runmate.runningevent.repository.RunningEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RunningEventParticipantService {
    private final RunningEventParticipantRepository runningEventParticipantRepository;
    private final MemberRepository memberRepository;
    private final RunningEventRepository runningEventRepository;

    @Transactional
    public RunningEventParticipantResponseDto participateRunningEvent(Long runningEventId, String memberEmail) {
        Member member = memberRepository.findByEmail(memberEmail).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        RunningEvent savedEvent = runningEventRepository.findById(runningEventId).orElseThrow(() -> new BusinessException(ErrorCode.RUNNING_EVENT_NOT_FOUND));

        if (runningEventParticipantRepository.existsByRunningEvent_IdAndMember_Id(runningEventId, member.getId())) {
            throw new BusinessException(ErrorCode.ALREADY_PARTICIPATING);
        }

        RunningEventParticipant runningEventParticipant = RunningEventParticipant.builder()
                .runningEvent(savedEvent)
                .member(member)
                .build();

        RunningEventParticipant savedRunningEventParticipant = runningEventParticipantRepository.save(runningEventParticipant);

        savedEvent.increaseCurrentParticipantCount();

        return RunningEventParticipantMapper.toResponse(savedRunningEventParticipant);
    }
}
