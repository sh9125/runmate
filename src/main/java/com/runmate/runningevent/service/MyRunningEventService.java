package com.runmate.runningevent.service;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.member.repository.MemberRepository;
import com.runmate.runningevent.dto.response.MyRunningEventResponseDto;
import com.runmate.runningevent.dto.response.RunningEventListResponseDto;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventParticipant;
import com.runmate.runningevent.entity.RunningEventWaitlistEntry;
import com.runmate.runningevent.mapper.MyRunningEventMapper;
import com.runmate.runningevent.mapper.RunningEventMapper;
import com.runmate.runningevent.repository.RunningEventRepository;
import com.runmate.runningevent.repository.RunningEventParticipantRepository;
import com.runmate.runningevent.repository.RunningEventWaitlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
// 목록과 순위를 같은 DB 스냅샷에서 읽어 중간의 취소·승급으로 정보가 엇갈리지 않게 한다.
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MyRunningEventService {
    private final MemberRepository memberRepository;
    private final RunningEventRepository eventRepository;
    private final RunningEventWaitlistRepository waitlistRepository;
    private final RunningEventParticipantRepository participantRepository;
    private final Clock clock;

    public Page<RunningEventListResponseDto> getCreatedEvents(String email, Pageable pageable) {
        Long memberId = findMemberId(email);
        return eventRepository.findByCreator_Id(memberId, stablePageable(pageable))
                .map(RunningEventMapper::toListResponse);
    }

    public Page<MyRunningEventResponseDto> getParticipatingEvents(String email, Pageable pageable) {
        Long memberId = findMemberId(email);
        return eventRepository.findMyParticipatingEvents(memberId, stablePageable(pageable))
                .map(MyRunningEventMapper::toParticipatingResponse);
    }

    public Page<MyRunningEventResponseDto> getWaitingEvents(String email, Pageable pageable) {
        Long memberId = findMemberId(email);
        LocalDateTime now = LocalDateTime.now(clock);
        Page<RunningEvent> events = eventRepository.findMyWaitingEvents(
                memberId, now.plusMinutes(10), stablePageable(pageable));
        if (events.isEmpty()) {
            return new PageImpl<>(List.of(), events.getPageable(), events.getTotalElements());
        }
        Map<Long, Long> positions = waitlistRepository.findMyPositions(memberId,
                        events.getContent().stream().map(RunningEvent::getId).toList()).stream()
                .collect(Collectors.toMap(RunningEventWaitlistRepository.WaitlistPosition::getEventId,
                        RunningEventWaitlistRepository.WaitlistPosition::getPosition));
        return events.map(event -> {
            Long position = positions.get(event.getId());
            if (position == null) {
                throw new IllegalStateException("대기 목록의 순위 정보를 찾을 수 없습니다.");
            }
            return MyRunningEventMapper.toWaitingResponse(event, position);
        });
    }

    public Page<MyRunningEventResponseDto> getEndedEvents(String email, Pageable pageable) {
        Long memberId = findMemberId(email);
        LocalDateTime now = LocalDateTime.now(clock);
        Page<RunningEvent> events = eventRepository.findMyEndedEvents(
                memberId, now.plusMinutes(10), stablePageable(pageable));
        if (events.isEmpty()) {
            return new PageImpl<>(List.of(), events.getPageable(), events.getTotalElements());
        }
        List<Long> eventIds = events.getContent().stream().map(RunningEvent::getId).toList();
        Map<Long, RunningEventParticipant> participants = participantRepository
                .findByMember_IdAndRunningEvent_IdIn(memberId, eventIds).stream()
                .collect(Collectors.toMap(p -> p.getRunningEvent().getId(), Function.identity()));
        Map<Long, RunningEventWaitlistEntry> entries = waitlistRepository
                .findByMember_IdAndRunningEvent_IdIn(memberId, eventIds).stream()
                .collect(Collectors.toMap(w -> w.getRunningEvent().getId(), Function.identity()));
        return events.map(event -> MyRunningEventMapper.toEndedResponse(event,
                RunningEventApplicationStateResolver.resolve(event, participants.get(event.getId()),
                        entries.get(event.getId()), now).endReason()));
    }

    private Long findMemberId(String email) {
        return memberRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND)).getId();
    }

    // 같은 시작 시각의 이벤트도 ID로 순서를 확정하여 페이지 경계에서 정렬이 흔들리지 않게 한다.
    private Pageable stablePageable(Pageable pageable) {
        if (pageable.isUnpaged()) {
            return pageable;
        }
        Sort sort = pageable.getSort().isUnsorted()
                ? Sort.by(Sort.Direction.DESC, "startAt") : pageable.getSort();
        if (sort.getOrderFor("id") == null) {
            sort = sort.and(Sort.by(Sort.Direction.DESC, "id"));
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
