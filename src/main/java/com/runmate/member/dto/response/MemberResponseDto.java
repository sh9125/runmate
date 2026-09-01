package com.runmate.member.dto.response;

import java.math.BigDecimal;

public record MemberResponseDto(
        String email,
        String name,
        String nickname,
        String region,
        Integer averagePaceSeconds,
        BigDecimal preferredDistanceKm
) {
}
