package com.runmate.member.entity;

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
@Table(name = "members")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(nullable = false, length = 30)
    private String name;

    @Column(nullable = false, unique = true, length = 30)
    private String nickname;

    @Column(nullable = false, length = 50)
    private String region;

    @Column(nullable = false)
    private Integer averagePaceSeconds;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal preferredDistanceKm;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Member(String email, String password, String name, String nickname, String region, Integer averagePaceSeconds, BigDecimal preferredDistanceKm) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.nickname = nickname;
        this.region = region;
        this.averagePaceSeconds = averagePaceSeconds;
        this.preferredDistanceKm = preferredDistanceKm;
    }

}
