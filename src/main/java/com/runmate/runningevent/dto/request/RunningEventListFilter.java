package com.runmate.runningevent.dto.request;

import com.runmate.runningevent.entity.RunningEventStatus;

import java.math.BigDecimal;

public record RunningEventListFilter(
        String keyword,
        String region,
        Integer minPace,
        Integer maxPace,
        BigDecimal minDistance,
        BigDecimal maxDistance,
        RunningEventStatus status
) {
    public RunningEventListFilter {
        keyword = keyword == null ? "" : keyword.strip();
        region = region == null ? "" : region.strip();
    }

    public boolean isEmpty() {
        return keyword.isEmpty() && region.isEmpty() && minPace == null && maxPace == null
                && minDistance == null && maxDistance == null && status == null;
    }
}
