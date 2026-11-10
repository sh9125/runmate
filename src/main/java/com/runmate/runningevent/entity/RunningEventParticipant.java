package com.runmate.runningevent.entity;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "running_event_participants",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_running_event_participant_event_member",
                        columnNames = {"running_event_id", "member_id"}
                )
        }
)
public class RunningEventParticipant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "running_event_id", nullable = false)
    private RunningEvent runningEvent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'PARTICIPATING'")
    private RunningEventParticipantStatus status;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 이전 데이터의 취소 시각은 복원할 수 없으므로 null을 허용한다.
    private LocalDateTime canceledAt;

    @Builder
    private RunningEventParticipant(RunningEvent runningEvent, Member member) {
        this.runningEvent = runningEvent;
        this.member = member;
        this.status = RunningEventParticipantStatus.PARTICIPATING;
    }

    public void cancel(LocalDateTime now) {
        if (status != RunningEventParticipantStatus.PARTICIPATING) {
            throw new BusinessException(ErrorCode.NOT_PARTICIPATING);
        }
        runningEvent.cancelParticipation(member, now);
        this.status = RunningEventParticipantStatus.CANCELED;
        this.canceledAt = now;
    }

    public void rejoin(LocalDateTime now) {
        if (status == RunningEventParticipantStatus.PARTICIPATING) {
            throw new BusinessException(ErrorCode.ALREADY_PARTICIPATING);
        }
        runningEvent.validateParticipationAllowed(now);
        runningEvent.increaseCurrentParticipantCount();
        this.status = RunningEventParticipantStatus.PARTICIPATING;
        this.canceledAt = null;
    }
}
