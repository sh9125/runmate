package com.runmate.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

public record LoginResponseDto(
        String accessToken,
        String tokenType
) {
}
