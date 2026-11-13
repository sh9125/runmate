package com.runmate.runningevent.repository;

import com.runmate.runningevent.entity.RunningEventWaitlistEntry;
import com.runmate.runningevent.entity.RunningEventWaitlistStatus;
import com.runmate.runningevent.entity.RunningEventParticipant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RunningEventWaitlistRepository extends JpaRepository<RunningEventWaitlistEntry, Long> {
    List<RunningEventWaitlistEntry> findByMember_IdAndRunningEvent_IdIn(Long memberId, List<Long> eventIds);

    // 페이지에 포함된 이벤트의 순위를 한 번에 조회한다. 과거 취소 행은 앞 순위에서 제외한다.
    @Query("""
            select entry.runningEvent.id as eventId,
                   (select count(ahead.id) + 1 from RunningEventWaitlistEntry ahead
                    where ahead.runningEvent = entry.runningEvent
                      and ahead.status = com.runmate.runningevent.entity.RunningEventWaitlistStatus.WAITING
                      and ahead.queueNumber < entry.queueNumber) as position
            from RunningEventWaitlistEntry entry
            where entry.runningEvent.id in :eventIds and entry.member.id = :memberId
              and entry.status = com.runmate.runningevent.entity.RunningEventWaitlistStatus.WAITING
            """)
    List<WaitlistPosition> findMyPositions(@Param("memberId") Long memberId,
                                          @Param("eventIds") List<Long> eventIds);

    interface WaitlistPosition {
        Long getEventId();
        Long getPosition();
    }

    Optional<RunningEventWaitlistEntry> findByRunningEvent_IdAndMember_Id(Long eventId, Long memberId);

    @Query("""
            select coalesce(max(entry.queueNumber), 0L) as lastQueueNumber,
                   coalesce(sum(case when entry.status = :waiting then 1L else 0L end), 0L) as waitingCount
            from RunningEventWaitlistEntry entry
            where entry.runningEvent.id = :eventId
            """)
    QueueTail findQueueTail(@Param("eventId") Long eventId, @Param("waiting") RunningEventWaitlistStatus waiting);

    interface QueueTail {
        long getLastQueueNumber();
        long getWaitingCount();
    }

    long countByRunningEvent_IdAndStatusAndQueueNumberLessThan(
            Long eventId, RunningEventWaitlistStatus status, long queueNumber);

    @Query("""
            select entry as entry, participant as participant
            from RunningEventWaitlistEntry entry
            left join RunningEventParticipant participant
              on participant.runningEvent = entry.runningEvent and participant.member = entry.member
            where entry.runningEvent.id = :eventId and entry.status = :status
            order by entry.queueNumber
            """)
    List<PromotionCandidate> findPromotionCandidates(@Param("eventId") Long eventId,
                                                     @Param("status") RunningEventWaitlistStatus status,
                                                     Pageable pageable);

    interface PromotionCandidate {
        RunningEventWaitlistEntry getEntry();
        RunningEventParticipant getParticipant();
    }

    List<RunningEventWaitlistEntry> findByRunningEvent_IdAndStatusOrderByQueueNumberAsc(
            Long eventId, RunningEventWaitlistStatus status, Pageable pageable);
}
