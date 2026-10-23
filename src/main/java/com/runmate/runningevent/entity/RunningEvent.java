package com.runmate.runningevent.entity;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
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
    private static final int MANAGEMENT_DEADLINE_MINUTES = 15;
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

    public void update(String title, String description, String region, String runningPlace,
                       String meetingPlace, LocalDateTime startAt, Integer preferredPaceSeconds,
                       BigDecimal targetDistanceKm, Integer capacity, LocalDateTime now) {
        validateManagementAllowed(now);

        if (!startAt.isAfter(now.plusMinutes(MANAGEMENT_DEADLINE_MINUTES))) {
            throw new BusinessException(ErrorCode.EVENT_START_TOO_SOON);
        }
        if (currentParticipantCount > 1 && (
                !this.startAt.equals(startAt) || !this.region.equals(region)
                        || !this.runningPlace.equals(runningPlace) || !this.meetingPlace.equals(meetingPlace))) {
            throw new BusinessException(ErrorCode.EVENT_DETAILS_LOCKED);
        }
        if (capacity < currentParticipantCount) {
            throw new BusinessException(ErrorCode.CAPACITY_BELOW_PARTICIPANTS);
        }

        // 모든 검증을 마친 뒤에만 값을 변경한다.
        this.title = title;
        this.description = description;
        this.region = region;
        this.runningPlace = runningPlace;
        this.meetingPlace = meetingPlace;
        this.startAt = startAt;
        this.preferredPaceSeconds = preferredPaceSeconds;
        this.targetDistanceKm = targetDistanceKm;
        this.capacity = capacity;
    }

    public void cancel(LocalDateTime now) {
        validateManagementAllowed(now);
        this.status = RunningEventStatus.CANCELED;
    }

    private void validateManagementAllowed(LocalDateTime now) {
        if (status != RunningEventStatus.RECRUITING && status != RunningEventStatus.CLOSED) {
            throw new BusinessException(ErrorCode.EVENT_NOT_MANAGEABLE);
        }
        if (!now.isBefore(startAt.minusMinutes(MANAGEMENT_DEADLINE_MINUTES))) {
            throw new BusinessException(ErrorCode.EVENT_MANAGEMENT_DEADLINE_PASSED);
        }
    }

}
