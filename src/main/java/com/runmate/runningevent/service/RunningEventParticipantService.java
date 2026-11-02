package com.runmate.runningevent.service;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import com.runmate.runningevent.dto.response.RunningEventParticipantResponseDto;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventParticipant;
import com.runmate.runningevent.entity.RunningEventStatus;
import com.runmate.runningevent.mapper.RunningEventParticipantMapper;
import com.runmate.runningevent.repository.RunningEventParticipantRepository;
import com.runmate.runningevent.repository.RunningEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RunningEventParticipantService {
    private final RunningEventParticipantRepository runningEventParticipantRepository;
    private final MemberRepository memberRepository;
    private final RunningEventRepository runningEventRepository;
    private final Clock clock;

    @Transactional
    public RunningEventParticipantResponseDto participateRunningEvent(Long runningEventId, String memberEmail) {
        Member member = memberRepository.findByEmail(memberEmail).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        RunningEvent savedEvent = runningEventRepository.findById(runningEventId).orElseThrow(() -> new BusinessException(ErrorCode.RUNNING_EVENT_NOT_FOUND));

        if (savedEvent.getStatus() == RunningEventStatus.CANCELED) {
            throw new BusinessException(ErrorCode.EVENT_CANCELED);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Optional<RunningEventParticipant> participant = runningEventParticipantRepository.findByRunningEvent_IdAndMember_Id(runningEventId, member.getId());
        if (participant.isEmpty()) {
            savedEvent.validateParticipationAllowed(now);
            RunningEventParticipant runningEventParticipant = RunningEventParticipant.builder()
                    .runningEvent(savedEvent)
                    .member(member)
                    .build();

            RunningEventParticipant savedRunningEventParticipant = runningEventParticipantRepository.save(runningEventParticipant);

            savedEvent.increaseCurrentParticipantCount();

            return RunningEventParticipantMapper.toResponse(savedRunningEventParticipant);
        }

        RunningEventParticipant existingParticipant = participant.get();
        existingParticipant.rejoin(now);
        return RunningEventParticipantMapper.toResponse(existingParticipant);
    }

    @Transactional
    public RunningEventParticipantResponseDto cancelParticipation(Long runningEventId, String memberEmail) {
        Member member = memberRepository.findByEmail(memberEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        runningEventRepository.findById(runningEventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUNNING_EVENT_NOT_FOUND));
        RunningEventParticipant participant = runningEventParticipantRepository
                .findByRunningEvent_IdAndMember_Id(runningEventId, member.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_PARTICIPATING));

        participant.cancel(LocalDateTime.now(clock));
        return RunningEventParticipantMapper.toResponse(participant);
    }
}
