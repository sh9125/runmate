package com.runmate.runningevent.repository;

import com.runmate.runningevent.entity.RunningEventParticipant;
import com.runmate.runningevent.entity.RunningEventParticipantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface RunningEventParticipantRepository extends JpaRepository<RunningEventParticipant, Long> {
    List<RunningEventParticipant> findByMember_IdAndRunningEvent_IdIn(Long memberId, List<Long> eventIds);

    Optional<RunningEventParticipant> findByRunningEvent_IdAndMember_Id(Long runningEventId, Long memberId);

    @Query("""
            select m.nickname from RunningEventParticipant p join p.member m
            where p.runningEvent.id = :eventId and p.status = :status
            order by p.id
            """)
    List<String> findNicknamesByEventAndStatus(Long eventId, RunningEventParticipantStatus status);
}
