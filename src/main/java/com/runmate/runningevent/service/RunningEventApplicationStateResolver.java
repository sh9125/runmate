package com.runmate.runningevent.service;

import com.runmate.runningevent.dto.response.MyRunningEventApplicationStatus;
import com.runmate.runningevent.dto.response.MyRunningEventEndReason;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventParticipant;
import com.runmate.runningevent.entity.RunningEventParticipantStatus;
import com.runmate.runningevent.entity.RunningEventStatus;
import com.runmate.runningevent.entity.RunningEventWaitlistEntry;
import com.runmate.runningevent.entity.RunningEventWaitlistStatus;

import java.time.LocalDateTime;

/** 상세와 마이페이지에서 같은 신청 상태·종료 사유 규칙을 사용한다. DB 조회는 하지 않는다. */
final class RunningEventApplicationStateResolver {
    private RunningEventApplicationStateResolver() {
    }

    static State resolve(RunningEvent event, RunningEventParticipant participant,
                         RunningEventWaitlistEntry entry, LocalDateTime now) {
        // 개최 취소된 이벤트라도 신청한 적이 없는 회원은 미신청이다.
        if (participant == null && entry == null) {
            return new State(MyRunningEventApplicationStatus.NONE, null);
        }
        if (event.getStatus() == RunningEventStatus.CANCELED) {
            return ended(MyRunningEventEndReason.EVENT_CANCELED);
        }
        if (participant != null && participant.getStatus() == RunningEventParticipantStatus.PARTICIPATING) {
            return new State(MyRunningEventApplicationStatus.PARTICIPATING, null);
        }
        if (entry != null && entry.getStatus() == RunningEventWaitlistStatus.WAITING) {
            return event.isWaitlistOpen(now)
                    ? new State(MyRunningEventApplicationStatus.WAITING, null)
                    : ended(MyRunningEventEndReason.WAITLIST_CLOSED);
        }
        if (participant != null && entry != null) {
            LocalDateTime participantCanceledAt = participant.getCanceledAt();
            LocalDateTime waitlistCanceledAt = entry.getCanceledAt();
            if (participantCanceledAt == null || waitlistCanceledAt == null
                    || participantCanceledAt.equals(waitlistCanceledAt)) {
                return ended(MyRunningEventEndReason.DIRECT_CANCELED);
            }
            return ended(participantCanceledAt.isAfter(waitlistCanceledAt)
                    ? MyRunningEventEndReason.PARTICIPATION_CANCELED
                    : MyRunningEventEndReason.WAITLIST_CANCELED);
        }
        return ended(entry != null ? MyRunningEventEndReason.WAITLIST_CANCELED
                : MyRunningEventEndReason.PARTICIPATION_CANCELED);
    }

    private static State ended(MyRunningEventEndReason reason) {
        return new State(MyRunningEventApplicationStatus.ENDED, reason);
    }

    record State(MyRunningEventApplicationStatus status, MyRunningEventEndReason endReason) {
    }
}
