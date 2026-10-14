package com.runmate.runningevent.controller;

import com.runmate.runningevent.dto.request.RunningEventCreateRequestDto;
import com.runmate.runningevent.dto.response.RunningEventResponseDto;
import com.runmate.runningevent.service.RunningEventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
}
