package com.runmate.runningevent.dto.response;

/** DB 저장 상태가 아니라 이벤트 상태와 참가·대기 기록으로 판단한 현재 신청 상태다. */
public enum MyRunningEventApplicationStatus {
    NONE,
    PARTICIPATING,
    WAITING,
    ENDED
}
