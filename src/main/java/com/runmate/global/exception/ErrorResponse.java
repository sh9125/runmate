package com.runmate.global.exception;

import java.util.List;

public record ErrorResponse(
        int status,
        String code,
        String message,
        List<ValidationError> errors
) {
    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.getStatus().value(), errorCode.name(),
                errorCode.getMessage(), List.of());
    }

    public record ValidationError(String field, String message) {
    }
}
