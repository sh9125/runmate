package com.runmate.runningevent.repository;

import com.runmate.runningevent.entity.RunningEventWaitlistEntry;
import com.runmate.runningevent.entity.RunningEventWaitlistStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RunningEventWaitlistRepository extends JpaRepository<RunningEventWaitlistEntry, Long> {
    Optional<RunningEventWaitlistEntry> findByRunningEvent_IdAndMember_Id(Long eventId, Long memberId);

    @Query("select coalesce(max(entry.queueNumber), 0) from RunningEventWaitlistEntry entry where entry.runningEvent.id = :eventId")
    long findLastQueueNumber(@Param("eventId") Long eventId);

    long countByRunningEvent_IdAndStatusAndQueueNumberLessThan(
            Long eventId, RunningEventWaitlistStatus status, long queueNumber);

    List<RunningEventWaitlistEntry> findByRunningEvent_IdAndStatusOrderByQueueNumberAsc(
            Long eventId, RunningEventWaitlistStatus status, Pageable pageable);
}
