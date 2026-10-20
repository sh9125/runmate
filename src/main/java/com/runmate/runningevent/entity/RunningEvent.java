package com.runmate.runningevent.entity;

import com.runmate.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "running_events")
@Getter
@EntityListeners(AuditingEntityListener.class)
public class RunningEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(nullable = false, length =255)
    private String region;

    @Column(nullable = false, length = 255)
    private String runningPlace;

    @Column(nullable = false, length = 255)
    private String meetingPlace;

    @Column(nullable = false)
    private LocalDateTime startAt;

    @Column(nullable = false)
    private Integer preferredPaceSeconds;

    @Column(nullable = false)
    private BigDecimal targetDistanceKm;

    @Column(nullable = false)
    private Integer capacity;

    @Column(nullable = false)
    private Integer currentParticipantCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunningEventStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = false)
    private Member creator;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private RunningEvent(String title, String description, String region, String runningPlace, String meetingPlace, LocalDateTime startAt, Integer preferredPaceSeconds, BigDecimal targetDistanceKm, Integer capacity, Member creator) {
        this.title = title;
        this.description = description;
        this.region = region;
        this.runningPlace = runningPlace;
        this.meetingPlace = meetingPlace;
        this.startAt = startAt;
        this.preferredPaceSeconds = preferredPaceSeconds;
        this.targetDistanceKm = targetDistanceKm;
        this.capacity = capacity;
        this.currentParticipantCount = 1;
        this.status = RunningEventStatus.RECRUITING;
        this.creator = creator;
    }

    public void increaseCurrentParticipantCount() {
        this.currentParticipantCount++;
    }

}
