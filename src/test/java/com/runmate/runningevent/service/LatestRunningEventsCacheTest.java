package com.runmate.runningevent.service;

import com.runmate.runningevent.dto.response.RunningEventListResponseDto;
import com.runmate.runningevent.entity.RunningEventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LatestRunningEventsCacheTest {
    @Test
    void refreshesRecruitmentStatusOnACacheHitAndReloadsAfterInvalidation() {
        var runningEvents = mock(RunningEventService.class);
        var clock = mock(Clock.class);
        var start = LocalDateTime.of(2026, 11, 13, 10, 0);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.instant()).thenReturn(start.minusSeconds(1).toInstant(ZoneOffset.UTC), start.toInstant(ZoneOffset.UTC));
        var firstPage = PageRequest.of(0, 9, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        var event = new RunningEventListResponseDto(1L, "Run", "Seoul", "Park", start,
                360, null, 10, 1, RunningEventStatus.RECRUITING, "Runner");
        when(runningEvents.getRunningEvents(firstPage)).thenReturn(new PageImpl<>(List.of(event)));
        var cache = new LatestRunningEventsCache(runningEvents, clock);

        assertThat(cache.supports(firstPage)).isTrue();
        assertThat(cache.supports(firstPage.next())).isFalse();
        assertThat(cache.supports(PageRequest.of(0, 9, Sort.by("id")))).isFalse();
        assertThat(cache.get(firstPage).getContent().get(0).status()).isEqualTo(RunningEventStatus.RECRUITING);
        assertThat(cache.get(firstPage).getContent().get(0).status()).isEqualTo(RunningEventStatus.CLOSED);
        verify(runningEvents).getRunningEvents(firstPage);

        cache.invalidate();
        cache.get(firstPage);
        verify(runningEvents, times(2)).getRunningEvents(firstPage);
    }
}
