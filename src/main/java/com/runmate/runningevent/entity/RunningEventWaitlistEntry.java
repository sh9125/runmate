package com.runmate.runningevent.entity;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "running_event_waitlist_entries",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_waitlist_entry_event_member",
                        columnNames = {"running_event_id", "member_id"}
                ),
                @UniqueConstraint(
                        name = "uk_waitlist_entry_event_queue_number",
                        columnNames = {"running_event_id", "queue_number"}
                )
        }
)
public class RunningEventWaitlistEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "running_event_id", nullable = false)
    private RunningEvent runningEvent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "queue_number", nullable = false)
    private long queueNumber;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunningEventWaitlistStatus status;

    @Builder
    private RunningEventWaitlistEntry(RunningEvent runningEvent, Member member,
                                      long queueNumber, LocalDateTime requestedAt) {
        this.runningEvent = runningEvent;
        this.member = member;
        startWaiting(queueNumber, requestedAt);
    }

    public void reapply(long queueNumber, LocalDateTime requestedAt) {
        if (status == RunningEventWaitlistStatus.WAITING) {
            throw new BusinessException(ErrorCode.ALREADY_WAITING);
        }
        startWaiting(queueNumber, requestedAt);
    }

    public void cancel() {
        if (status != RunningEventWaitlistStatus.WAITING) {
            throw new BusinessException(ErrorCode.NOT_WAITING);
        }
        this.status = RunningEventWaitlistStatus.CANCELED;
    }

    private void startWaiting(long queueNumber, LocalDateTime requestedAt) {
        this.queueNumber = queueNumber;
        this.requestedAt = requestedAt;
        this.status = RunningEventWaitlistStatus.WAITING;
    }
}
