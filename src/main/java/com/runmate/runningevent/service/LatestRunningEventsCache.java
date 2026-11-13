package com.runmate.runningevent.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.runmate.runningevent.dto.response.RunningEventListResponseDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "runmate.events.latest-cache-enabled", havingValue = "true", matchIfMissing = true)
public class LatestRunningEventsCache {
    private static final Sort LATEST_SORT = Sort.by(Sort.Direction.DESC, "createdAt", "id");
    private static final String FIRST_PAGE = "latest-first-page";

    private final RunningEventService runningEvents;
    private final Clock clock;
    private final Cache<String, Page<RunningEventListResponseDto>> pages = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(Duration.ofSeconds(5))
            .build();

    public LatestRunningEventsCache(RunningEventService runningEvents, Clock clock) {
        this.runningEvents = runningEvents;
        this.clock = clock;
    }

    public boolean supports(Pageable pageable) {
        return pageable.isPaged() && pageable.getPageNumber() == 0 && pageable.getPageSize() == 9
                && pageable.getSort().equals(LATEST_SORT);
    }

    public Page<RunningEventListResponseDto> get(Pageable pageable) {
        // 읽기 트랜잭션이 끝난 뒤 결과를 저장한다. 동시에 캐시를 조회해도 같은 키는 한 번만 채운다.
        var page = pages.get(FIRST_PAGE, key -> runningEvents.getRunningEvents(pageable));
        LocalDateTime now = LocalDateTime.now(clock);
        return page.map(event -> event.withRecruitmentStatusAt(now));
    }

    public void invalidate() {
        // 같은 키의 캐시 채우기와 삭제가 겹치면 순서대로 처리한다.
        pages.asMap().compute(FIRST_PAGE, (key, page) -> null);
    }
}
