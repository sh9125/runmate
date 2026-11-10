package com.runmate.runningevent.dto.response;

/**
 * 내 신청 목록의 한 항목. 같은 이벤트는 현재 신청 상태에 해당하는 목록에만 표시한다.
 * 목록의 페이징에는 기존 API와 같이 Page를 사용한다.
 */
public record MyRunningEventResponseDto(
        RunningEventListResponseDto event,
        MyRunningEventApplicationStatus applicationStatus,
        // ENDED일 때만 제공한다.
        MyRunningEventEndReason endReason,
        // 유효한 WAITING 상태일 때만 현재 순위를 제공한다.
        Long waitlistPosition
) {
}
