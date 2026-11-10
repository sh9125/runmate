package com.runmate.auth.repository;

import com.runmate.auth.entity.RefreshSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.tokenHash = :hash")
    Optional<RefreshSession> findForRefresh(@Param("hash") String hash);

    @Modifying
    @Query("delete from RefreshSession s where s.tokenHash = :hash")
    void revoke(@Param("hash") String hash);

    @Modifying
    @Query("delete from RefreshSession s where s.member.id = :memberId and s.expiresAt <= :now")
    void deleteExpiredForMember(@Param("memberId") Long memberId, @Param("now") Instant now);
}
