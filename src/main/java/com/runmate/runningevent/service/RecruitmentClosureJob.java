package com.runmate.runningevent.service;

import com.runmate.runningevent.repository.RunningEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class RecruitmentClosureJob {
    private final RunningEventRepository events;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${runmate.recruitment.closure-delay:10s}")
    @Transactional
    public void closeStartedRecruitment() {
        events.closeStartedRecruitment(LocalDateTime.now(clock));
    }
}
