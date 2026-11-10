package com.runmate.auth.entity;

import com.runmate.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "auth_refresh_sessions", indexes = @Index(columnList = "member_id"))
public class RefreshSession {
    @Id
    @Column(length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false)
    private Instant expiresAt;

    public RefreshSession(String tokenHash, Member member, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.member = member;
        this.expiresAt = expiresAt;
    }
}
