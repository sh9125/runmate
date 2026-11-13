package com.runmate.runningevent.service;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import com.runmate.runningevent.dto.response.RunningEventApplicationResponse;
import com.runmate.runningevent.dto.response.RunningEventParticipantResponseDto;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventParticipant;
import com.runmate.runningevent.entity.RunningEventParticipantStatus;
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
    private final RunningEventWaitlistService waitlistService;

    @Transactional
    public RunningEventApplicationResponse participateRunningEvent(Long runningEventId, String memberEmail) {
        Member member = memberRepository.findByEmail(memberEmail).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        RunningEvent savedEvent = runningEventRepository.findByIdForUpdate(runningEventId).orElseThrow(() -> new BusinessException(ErrorCode.RUNNING_EVENT_NOT_FOUND));

        if (savedEvent.getStatus() == RunningEventStatus.CANCELED) {
            throw new BusinessException(ErrorCode.EVENT_CANCELED);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Optional<RunningEventParticipant> participant = runningEventParticipantRepository.findByRunningEvent_IdAndMember_Id(runningEventId, member.getId());
        if (participant.isPresent() && participant.get().getStatus() == RunningEventParticipantStatus.PARTICIPATING) {
            throw new BusinessException(ErrorCode.ALREADY_PARTICIPATING);
        }
        savedEvent.validateRecruitmentOpen(now);
        if (savedEvent.getCurrentParticipantCount() >= savedEvent.getCapacity()) {
            return waitlistService.enqueue(savedEvent, member, now);
        }
        if (participant.isEmpty()) {
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
        RunningEvent event = runningEventRepository.findByIdForUpdate(runningEventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUNNING_EVENT_NOT_FOUND));
        RunningEventParticipant participant = runningEventParticipantRepository
                .findByRunningEvent_IdAndMember_Id(runningEventId, member.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_PARTICIPATING));

        LocalDateTime now = LocalDateTime.now(clock);
        participant.validateCancellation(now);
        // 참가 기록을 먼저 읽어 취소와 승급 사이에 중간 인원이 flush되지 않도록 한다.
        var promotion = waitlistService.prepareNextPromotion(event, now);
        participant.cancel(now);
        promotion.ifPresent(next -> {
            waitlistService.applyPromotion(event, now, next);
            // 인원 수가 같아도 참가 명단이 바뀌었으므로 수정 시각을 갱신한다.
            event.markParticipantRosterChanged(now);
        });
        return RunningEventParticipantMapper.toResponse(participant);
    }
}
