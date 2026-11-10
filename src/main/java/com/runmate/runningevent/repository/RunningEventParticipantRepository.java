package com.runmate.runningevent.repository;

import com.runmate.runningevent.entity.RunningEventParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface RunningEventParticipantRepository extends JpaRepository<RunningEventParticipant, Long> {
    List<RunningEventParticipant> findByMember_IdAndRunningEvent_IdIn(Long memberId, List<Long> eventIds);

    Optional<RunningEventParticipant> findByRunningEvent_IdAndMember_Id(Long runningEventId, Long memberId);
}
