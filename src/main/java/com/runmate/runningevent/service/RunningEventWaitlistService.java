package com.runmate.runningevent.service;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import com.runmate.runningevent.dto.response.RunningEventWaitlistResponseDto;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventParticipant;
import com.runmate.runningevent.entity.RunningEventWaitlistEntry;
import com.runmate.runningevent.mapper.RunningEventWaitlistMapper;
import com.runmate.runningevent.repository.RunningEventRepository;
import com.runmate.runningevent.repository.RunningEventParticipantRepository;
import com.runmate.runningevent.repository.RunningEventWaitlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.runmate.runningevent.entity.RunningEventWaitlistStatus.WAITING;

@Service
@RequiredArgsConstructor
public class RunningEventWaitlistService {
    private final RunningEventWaitlistRepository waitlistRepository;
    private final RunningEventRepository eventRepository;
    private final MemberRepository memberRepository;
    private final RunningEventParticipantRepository participantRepository;
    private final Clock clock;

    // 호출하는 서비스가 이벤트 행을 잠근 트랜잭션 안에서 실행한다.
    RunningEventWaitlistResponseDto enqueue(RunningEvent event, Member member, LocalDateTime now) {
        requireOpen(event, now);
        Optional<RunningEventWaitlistEntry> existing = waitlistRepository
                .findByRunningEvent_IdAndMember_Id(event.getId(), member.getId());
        if (existing.isPresent() && existing.get().getStatus() == WAITING) {
            throw new BusinessException(ErrorCode.ALREADY_WAITING);
        }

        var queueTail = waitlistRepository.findQueueTail(event.getId(), WAITING);
        long queueNumber = queueTail.getLastQueueNumber() + 1;
        RunningEventWaitlistEntry entry;
        if (existing.isPresent()) {
            entry = existing.get();
            entry.reapply(queueNumber, now);
        } else {
            entry = waitlistRepository.save(RunningEventWaitlistEntry.builder()
                    .runningEvent(event).member(member).queueNumber(queueNumber).requestedAt(now).build());
        }
        // 모임 행 락 안에서 대기열 맨 뒤에 추가하므로 기존 WAITING 행이 모두 앞에 있다.
        return RunningEventWaitlistMapper.toResponse(entry, queueTail.getWaitingCount() + 1);
    }

    @Transactional
    public RunningEventWaitlistResponseDto cancelWaitlist(Long eventId, String email) {
        RunningEventWaitlistEntry entry = findActiveEntry(eventId, email);
        entry.cancel(LocalDateTime.now(clock));
        return RunningEventWaitlistMapper.toResponse(entry, null);
    }

    @Transactional
    public RunningEventWaitlistResponseDto getMyWaitlistPosition(Long eventId, String email) {
        return toRankedResponse(findActiveEntry(eventId, email));
    }

    private RunningEventWaitlistEntry findActiveEntry(Long eventId, String email) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        RunningEvent event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUNNING_EVENT_NOT_FOUND));
        requireOpen(event, LocalDateTime.now(clock));
        return waitlistRepository.findByRunningEvent_IdAndMember_Id(eventId, member.getId())
                .filter(entry -> entry.getStatus() == WAITING)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_WAITING));
    }

    // 호출하는 서비스의 이벤트 행 락과 트랜잭션을 그대로 사용한다.
    Optional<PreparedPromotion> prepareNextPromotion(RunningEvent event, LocalDateTime now) {
        if (!event.isWaitlistOpen(now)) {
            return Optional.empty();
        }
        return waitlistRepository.findPromotionCandidates(
                        event.getId(), WAITING, PageRequest.of(0, 1))
                .stream().findFirst()
                .map(candidate -> new PreparedPromotion(candidate.getEntry(),
                        Optional.ofNullable(candidate.getParticipant())));
    }

    void promote(RunningEvent event, LocalDateTime now, int seats) {
        if (seats <= 0 || !event.isWaitlistOpen(now)) {
            return;
        }
        List<RunningEventWaitlistEntry> entries = waitlistRepository.findByRunningEvent_IdAndStatusOrderByQueueNumberAsc(
                event.getId(), WAITING, PageRequest.of(0, seats));
        for (RunningEventWaitlistEntry entry : entries) {
            applyPromotion(event, now, preparePromotion(entry));
        }
    }

    private PreparedPromotion preparePromotion(RunningEventWaitlistEntry entry) {
        return new PreparedPromotion(entry, participantRepository.findByRunningEvent_IdAndMember_Id(
                entry.getRunningEvent().getId(), entry.getMember().getId()));
    }

    void applyPromotion(RunningEvent event, LocalDateTime now, PreparedPromotion promotion) {
        RunningEventWaitlistEntry entry = promotion.entry();
        if (promotion.participant().isPresent()) {
            promotion.participant().get().rejoin(now);
        } else {
            event.increaseCurrentParticipantCount();
            participantRepository.save(RunningEventParticipant.builder()
                    .runningEvent(event).member(entry.getMember()).build());
        }
        waitlistRepository.delete(entry);
    }

    record PreparedPromotion(RunningEventWaitlistEntry entry, Optional<RunningEventParticipant> participant) {
    }

    private RunningEventWaitlistResponseDto toRankedResponse(RunningEventWaitlistEntry entry) {
        long ahead = waitlistRepository.countByRunningEvent_IdAndStatusAndQueueNumberLessThan(
                entry.getRunningEvent().getId(), WAITING, entry.getQueueNumber());
        return RunningEventWaitlistMapper.toResponse(entry, ahead + 1);
    }

    private void requireOpen(RunningEvent event, LocalDateTime now) {
        if (!event.isWaitlistOpen(now)) {
            throw new BusinessException(ErrorCode.EVENT_WAITLIST_CLOSED);
        }
    }
}
