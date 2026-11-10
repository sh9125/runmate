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

    @Query("select coalesce(max(entry.queueNumber), 0) from RunningEventWaitlistEntry entry where entry.runningEvent.id = :eventId")
    long findLastQueueNumber(@Param("eventId") Long eventId);

    long countByRunningEvent_IdAndStatusAndQueueNumberLessThan(
            Long eventId, RunningEventWaitlistStatus status, long queueNumber);

    List<RunningEventWaitlistEntry> findByRunningEvent_IdAndStatusOrderByQueueNumberAsc(
            Long eventId, RunningEventWaitlistStatus status, Pageable pageable);
}
