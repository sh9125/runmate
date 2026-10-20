package com.runmate.runningevent.repository;

import com.runmate.runningevent.entity.RunningEventParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RunningEventParticipantRepository extends JpaRepository<RunningEventParticipant, Long> {
    boolean existsByRunningEvent_IdAndMember_Id(Long runningEventId, Long memberId);

}
