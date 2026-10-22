package com.runmate.global.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
        return respond(exception.getErrorCode());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException exception) {
        return respond(ErrorCode.FORBIDDEN);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException exception) {
        return respond(ErrorCode.UNAUTHORIZED);
    }

    // Spring MVC의 검증/JSON 파싱/타입 변환 오류도 같은 응답 형식으로 변환한다.
    // 405의 Allow 같은 프레임워크 헤더와 원래 HTTP 상태는 보존한다.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ErrorCode errorCode = switch (status.value()) {
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError()
                    ? ErrorCode.INTERNAL_SERVER_ERROR : ErrorCode.INVALID_REQUEST;
        };
        List<ErrorResponse.ValidationError> errors = List.of();

        if (exception instanceof MethodArgumentNotValidException validationException) {
            errorCode = ErrorCode.VALIDATION_FAILED;
            errors = validationException.getBindingResult().getFieldErrors().stream()
                    .map(error -> new ErrorResponse.ValidationError(error.getField(), error.getDefaultMessage()))
                    .toList();
        }

        if (status.is5xxServerError()) {
            log.error("Spring MVC 요청 처리 중 서버 오류", exception);
        }

        ErrorResponse response = new ErrorResponse(status.value(), errorCode.name(),
                errorCode.getMessage(), errors);
        return super.handleExceptionInternal(exception, response, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        // 내부 예외 메시지나 SQL을 클라이언트에 노출하지 않는다.
        log.error("처리하지 못한 서버 오류", exception);
        return respond(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }
}
