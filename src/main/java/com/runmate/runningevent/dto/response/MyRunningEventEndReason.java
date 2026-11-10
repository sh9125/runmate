package com.runmate.runningevent.dto.response;

/** 취소·종료된 신청을 화면에서 구분해서 보여주기 위한 사유다. */
public enum MyRunningEventEndReason {
    PARTICIPATION_CANCELED,
    WAITLIST_CANCELED,
    // 과거 기록에 취소 시각이 없거나 두 시각이 같아 마지막 취소 종류를 구분할 수 없는 경우.
    DIRECT_CANCELED,
    EVENT_CANCELED,
    WAITLIST_CLOSED
}
