package com.runmate.runningevent.controller;

import com.runmate.runningevent.dto.response.MyRunningEventResponseDto;
import com.runmate.runningevent.dto.response.RunningEventListResponseDto;
import com.runmate.runningevent.service.MyRunningEventService;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members/me/running-events")
public class MyRunningEventController {
    private final MyRunningEventService myRunningEventService;

    @GetMapping("/created")
    public ResponseEntity<Page<RunningEventListResponseDto>> getCreatedEvents(
            Authentication authentication, @ParameterObject Pageable pageable) {
        return ResponseEntity.ok(myRunningEventService.getCreatedEvents(authentication.getName(), pageable));
    }

    @GetMapping("/participating")
    public ResponseEntity<Page<MyRunningEventResponseDto>> getParticipatingEvents(
            Authentication authentication, @ParameterObject Pageable pageable) {
        return ResponseEntity.ok(myRunningEventService.getParticipatingEvents(authentication.getName(), pageable));
    }

    @GetMapping("/waiting")
    public ResponseEntity<Page<MyRunningEventResponseDto>> getWaitingEvents(
            Authentication authentication, @ParameterObject Pageable pageable) {
        return ResponseEntity.ok(myRunningEventService.getWaitingEvents(authentication.getName(), pageable));
    }

    @GetMapping("/ended")
    public ResponseEntity<Page<MyRunningEventResponseDto>> getEndedEvents(
            Authentication authentication, @ParameterObject Pageable pageable) {
        return ResponseEntity.ok(myRunningEventService.getEndedEvents(authentication.getName(), pageable));
    }
}
