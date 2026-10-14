package com.runmate.runningevent.controller;

import com.runmate.runningevent.dto.request.RunningEventCreateRequestDto;
import com.runmate.runningevent.dto.response.RunningEventListResponseDto;
import com.runmate.runningevent.dto.response.RunningEventResponseDto;
import com.runmate.runningevent.service.RunningEventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/running-events")
public class RunningEventController {
    private final RunningEventService runningEventService;

    @PostMapping
    public ResponseEntity<RunningEventResponseDto> createRunningEvent(
            @Valid @RequestBody RunningEventCreateRequestDto runningEventCreateRequestDto,
            Authentication authentication
    ) {
        RunningEventResponseDto response = runningEventService.createRunningEvent(runningEventCreateRequestDto, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<Page<RunningEventListResponseDto>> getRunningEvents(@ParameterObject Pageable pageable) {
        Page<RunningEventListResponseDto> runningEvents = runningEventService.getRunningEvents(pageable);
        return ResponseEntity.ok(runningEvents);
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<RunningEventResponseDto> getRunningEvent(@PathVariable Long eventId) {
        RunningEventResponseDto runningEvent = runningEventService.getRunningEvent(eventId);
        return ResponseEntity.ok(runningEvent);
    }
}
