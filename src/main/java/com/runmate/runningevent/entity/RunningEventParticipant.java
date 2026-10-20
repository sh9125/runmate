package com.runmate.runningevent.entity;

import com.runmate.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
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

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private RunningEventParticipant(RunningEvent runningEvent, Member member) {
        this.runningEvent = runningEvent;
        this.member = member;
    }
}
