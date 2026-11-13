package com.runmate.runningevent.controller;

import com.runmate.global.config.RunningEventWriteGate;
import com.runmate.runningevent.dto.request.RunningEventCreateRequestDto;
import com.runmate.runningevent.dto.request.RunningEventListFilter;
import com.runmate.runningevent.dto.request.RunningEventUpdateRequestDto;
import com.runmate.runningevent.dto.response.RunningEventApplicationResponse;
import com.runmate.runningevent.dto.response.RunningEventListResponseDto;
import com.runmate.runningevent.dto.response.RunningEventParticipantResponseDto;
import com.runmate.runningevent.dto.response.RunningEventResponseDto;
import com.runmate.runningevent.dto.response.RunningEventWaitlistResponseDto;
import com.runmate.runningevent.service.RunningEventParticipantService;
import com.runmate.runningevent.service.LatestRunningEventsCache;
import com.runmate.runningevent.service.RunningEventService;
import com.runmate.runningevent.service.RunningEventWaitlistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.function.Supplier;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/running-events")
public class RunningEventController {
    private final RunningEventService runningEventService;
    private final RunningEventParticipantService runningEventParticipantService;
    private final RunningEventWaitlistService waitlistService;
    private final ObjectProvider<RunningEventWriteGate> writeGate;
    private final ObjectProvider<LatestRunningEventsCache> latestEventsCache;

    @PostMapping
    public ResponseEntity<RunningEventResponseDto> createRunningEvent(
            @Valid @RequestBody RunningEventCreateRequestDto runningEventCreateRequestDto,
            Authentication authentication
    ) {
        RunningEventResponseDto response = runningEventService.createRunningEvent(runningEventCreateRequestDto, authentication.getName());
        invalidateLatestEvents();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<Page<RunningEventListResponseDto>> getRunningEvents(
            @ParameterObject @ModelAttribute RunningEventListFilter filter,
            @ParameterObject @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
            Pageable pageable) {
        if (!filter.isEmpty()) {
            return ResponseEntity.ok(runningEventService.getRunningEvents(pageable, filter));
        }
        LatestRunningEventsCache cache = latestEventsCache.getIfAvailable();
        return ResponseEntity.ok(cache != null && cache.supports(pageable)
                ? cache.get(pageable) : runningEventService.getRunningEvents(pageable));
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<RunningEventResponseDto> getRunningEvent(
            @PathVariable Long eventId, Authentication authentication) {
        RunningEventResponseDto runningEvent = runningEventService.getRunningEvent(eventId, authentication.getName());
        return ResponseEntity.ok(runningEvent);
    }

    @PutMapping("/{eventId}")
    public ResponseEntity<RunningEventResponseDto> updateRunningEvent(
            @PathVariable Long eventId,
            @Valid @RequestBody RunningEventUpdateRequestDto request,
            Authentication authentication
    ) {
        var response = runningEventService.updateRunningEvent(eventId, request, authentication.getName());
        invalidateLatestEvents();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{eventId}/cancel")
    public ResponseEntity<RunningEventResponseDto> cancelRunningEvent(
            @PathVariable Long eventId, Authentication authentication
    ) {
        var response = runningEventService.cancelRunningEvent(eventId, authentication.getName());
        invalidateLatestEvents();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{eventId}/complete")
    public ResponseEntity<RunningEventResponseDto> completeRunningEvent(
            @PathVariable Long eventId, Authentication authentication
    ) {
        var response = runningEventService.completeRunningEvent(eventId, authentication.getName());
        invalidateLatestEvents();
        return ResponseEntity.ok(response);
    }

    private void invalidateLatestEvents() {
        // 서비스 호출이 성공적으로 반환되면 트랜잭션 커밋도 끝난 상태다.
        latestEventsCache.ifAvailable(LatestRunningEventsCache::invalidate);
    }

    @PostMapping("/{eventId}/participants")
    public ResponseEntity<RunningEventApplicationResponse> participateRunningEvent(@PathVariable Long eventId, Authentication authentication) {
        RunningEventApplicationResponse response = runParticipantWrite(eventId,
                () -> runningEventParticipantService.participateRunningEvent(eventId, authentication.getName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{eventId}/participants/cancel")
    public ResponseEntity<RunningEventParticipantResponseDto> cancelParticipation(
            @PathVariable Long eventId, Authentication authentication
    ) {
        return ResponseEntity.ok(runParticipantWrite(eventId,
                () -> runningEventParticipantService.cancelParticipation(eventId, authentication.getName())));
    }

    private <T> T runParticipantWrite(long eventId, Supplier<T> write) {
        // 트랜잭션 프록시를 호출하기 전에 차례를 기다린다. 프록시 반환 시에는 커밋도 끝났다.
        RunningEventWriteGate gate = writeGate.getIfAvailable();
        return gate == null ? write.get() : gate.execute(eventId, write);
    }

    @GetMapping("/{eventId}/waitlist/me")
    public ResponseEntity<RunningEventWaitlistResponseDto> getMyWaitlistPosition(
            @PathVariable Long eventId, Authentication authentication
    ) {
        return ResponseEntity.ok(waitlistService.getMyWaitlistPosition(eventId, authentication.getName()));
    }

    @DeleteMapping("/{eventId}/waitlist/me")
    public ResponseEntity<RunningEventWaitlistResponseDto> cancelWaitlist(
            @PathVariable Long eventId, Authentication authentication
    ) {
        return ResponseEntity.ok(waitlistService.cancelWaitlist(eventId, authentication.getName()));
    }
}
