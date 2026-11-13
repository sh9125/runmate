package com.runmate.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 회원입니다."),
    RUNNING_EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 러닝 이벤트입니다."),
    NOT_EVENT_CREATOR(HttpStatus.FORBIDDEN, "이벤트 생성자만 수정하거나 취소할 수 있습니다."),
    EVENT_NOT_MANAGEABLE(HttpStatus.CONFLICT, "수정하거나 취소할 수 없는 이벤트 상태입니다."),
    EVENT_NOT_COMPLETABLE(HttpStatus.CONFLICT, "시작 시각이 지난 모집 마감 모임만 완료할 수 있습니다."),
    EVENT_MANAGEMENT_DEADLINE_PASSED(HttpStatus.CONFLICT, "이벤트 시작 15분 전부터 수정하거나 취소할 수 없습니다."),
    EVENT_START_TOO_SOON(HttpStatus.CONFLICT, "변경할 시작 시각은 현재 시각보다 15분 넘게 뒤여야 합니다."),
    EVENT_DETAILS_LOCKED(HttpStatus.CONFLICT, "다른 참가자가 있으면 이벤트 날짜와 장소를 변경할 수 없습니다."),
    CAPACITY_BELOW_PARTICIPANTS(HttpStatus.CONFLICT, "정원은 현재 참가 인원보다 작게 설정할 수 없습니다."),
    EVENT_CANCELED(HttpStatus.CONFLICT, "취소된 이벤트에는 참가할 수 없습니다."),
    EVENT_NOT_RECRUITING(HttpStatus.CONFLICT, "모집 중인 이벤트에만 참가할 수 있습니다."),
    EVENT_ALREADY_STARTED(HttpStatus.CONFLICT, "이벤트 시작 시각부터 참가할 수 없습니다."),
    EVENT_CAPACITY_FULL(HttpStatus.CONFLICT, "이벤트 참가 정원이 가득 찼습니다."),
    CREATOR_CANNOT_CANCEL_PARTICIPATION(HttpStatus.CONFLICT, "생성자는 개인 참가를 취소할 수 없습니다. 개최 취소를 이용해 주세요."),
    PARTICIPATION_NOT_CANCELABLE(HttpStatus.CONFLICT, "개인 참가를 취소할 수 없는 이벤트 상태입니다."),
    PARTICIPATION_CANCELLATION_DEADLINE_PASSED(HttpStatus.CONFLICT, "이벤트 시작 10분 전부터 개인 참가를 취소할 수 없습니다."),
    NOT_PARTICIPATING(HttpStatus.CONFLICT, "현재 참가 중인 이벤트가 아닙니다."),
    EVENT_WRITE_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "모임 신청·취소 요청이 몰리고 있습니다. 잠시 후 다시 시도해 주세요."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일 입니다."),
    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "이미 사용 중인 닉네임 입니다."),
    PASSWORD_TOO_LONG(HttpStatus.BAD_REQUEST, "비밀번호가 너무 깁니다. 길이를 줄여 주세요."),
    PASSWORD_TOO_SHORT(HttpStatus.BAD_REQUEST, "비밀번호는 8자 이상 입력해 주세요."),
    PASSWORD_CHANGE_FIELDS_REQUIRED(HttpStatus.BAD_REQUEST, "현재 비밀번호와 새 비밀번호를 모두 입력해 주세요."),
    INCORRECT_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다."),
    ALREADY_PARTICIPATING(HttpStatus.CONFLICT, "이미 참가한 러닝 이벤트 입니다."),
    ALREADY_WAITING(HttpStatus.CONFLICT, "이미 대기 중인 이벤트입니다."),
    NOT_WAITING(HttpStatus.CONFLICT, "현재 대기 중인 이벤트가 아닙니다."),
    EVENT_WAITLIST_CLOSED(HttpStatus.CONFLICT, "대기가 종료된 이벤트입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "올바르지 않은 요청입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;
}
