package com.runmate.runningevent.repository;

import com.runmate.runningevent.dto.request.RunningEventListFilter;
import com.runmate.runningevent.entity.RunningEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface RunningEventRepository extends JpaRepository<RunningEvent, Long> {
    // 이벤트를 기준으로 조회하므로 참가·대기 기록이 함께 있어도 이벤트가 중복되지 않는다.
    // 생성자는 목록 DTO에 필요하며, 단일 연관관계를 함께 조회해 추가 쿼리를 방지한다.
    @EntityGraph(attributePaths = "creator")
    Page<RunningEvent> findByCreator_Id(Long memberId, Pageable pageable);

    // 과거 참가도 포함한다. 개최 취소는 참가 기록을 보존하므로 별도로 제외한다.
    @EntityGraph(attributePaths = "creator")
    @Query("""
            select e from RunningEvent e
            where e.status <> com.runmate.runningevent.entity.RunningEventStatus.CANCELED
              and exists (
                  select p.id from RunningEventParticipant p
                  where p.runningEvent = e and p.member.id = :memberId
                    and p.status = com.runmate.runningevent.entity.RunningEventParticipantStatus.PARTICIPATING
              )
            """)
    Page<RunningEvent> findMyParticipatingEvents(@Param("memberId") Long memberId, Pageable pageable);

    // waitlistOpenAfter는 서비스에서 한 번 구한 현재 시각 + 10분이다.
    // startAt > 경계값이므로 정확히 시작 10분 전부터는 대기 목록에서 제외한다.
    @EntityGraph(attributePaths = "creator")
    @Query("""
            select e from RunningEvent e
            where e.status = com.runmate.runningevent.entity.RunningEventStatus.RECRUITING
              and e.startAt > :waitlistOpenAfter
              and exists (
                  select w.id from RunningEventWaitlistEntry w
                  where w.runningEvent = e and w.member.id = :memberId
                    and w.status = com.runmate.runningevent.entity.RunningEventWaitlistStatus.WAITING
              )
              and not exists (
                  select p.id from RunningEventParticipant p
                  where p.runningEvent = e and p.member.id = :memberId
                    and p.status = com.runmate.runningevent.entity.RunningEventParticipantStatus.PARTICIPATING
              )
            """)
    Page<RunningEvent> findMyWaitingEvents(@Param("memberId") Long memberId,
                                           @Param("waitlistOpenAfter") LocalDateTime waitlistOpenAfter,
                                           Pageable pageable);

    // 신청 기록이 있고 현재 참가·유효 대기 어디에도 속하지 않는 이벤트다.
    // 개최 취소가 최우선이며, 분류를 DB에서 끝낸 뒤 페이징하여 전체 개수도 일치시킨다.
    @EntityGraph(attributePaths = "creator")
    @Query("""
            select e from RunningEvent e
            where (
                exists (select p.id from RunningEventParticipant p
                        where p.runningEvent = e and p.member.id = :memberId)
                or exists (select w.id from RunningEventWaitlistEntry w
                           where w.runningEvent = e and w.member.id = :memberId)
            )
            and (
                e.status = com.runmate.runningevent.entity.RunningEventStatus.CANCELED
                or (
                    not exists (
                        select p.id from RunningEventParticipant p
                        where p.runningEvent = e and p.member.id = :memberId
                          and p.status = com.runmate.runningevent.entity.RunningEventParticipantStatus.PARTICIPATING
                    )
                    and not exists (
                        select w.id from RunningEventWaitlistEntry w
                        where w.runningEvent = e and w.member.id = :memberId
                          and w.status = com.runmate.runningevent.entity.RunningEventWaitlistStatus.WAITING
                          and e.status = com.runmate.runningevent.entity.RunningEventStatus.RECRUITING
                          and e.startAt > :waitlistOpenAfter
                    )
                )
            )
            """)
    Page<RunningEvent> findMyEndedEvents(@Param("memberId") Long memberId,
                                         @Param("waitlistOpenAfter") LocalDateTime waitlistOpenAfter,
                                         Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from RunningEvent e where e.id = :id")
    Optional<RunningEvent> findByIdForUpdate(@Param("id") Long id);

    @Override
    @EntityGraph(attributePaths = "creator")
    Page<RunningEvent> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "creator")
    @Query("""
            select e from RunningEvent e
            where (:#{#filter.keyword} = ''
                or locate(lower(:#{#filter.keyword}), lower(e.title)) > 0
                or locate(lower(:#{#filter.keyword}), lower(e.description)) > 0
                or locate(lower(:#{#filter.keyword}), lower(e.runningPlace)) > 0)
              and (:#{#filter.region} = '' or lower(e.region) = lower(:#{#filter.region}))
              and coalesce(e.preferredPaceSeconds >= :#{#filter.minPace}, true)
              and coalesce(e.preferredPaceSeconds < :#{#filter.maxPace}, true)
              and coalesce(e.targetDistanceKm >= :#{#filter.minDistance}, true)
              and coalesce(e.targetDistanceKm < :#{#filter.maxDistance}, true)
              and (:statusName = '' or :statusName = case
                  when e.status = com.runmate.runningevent.entity.RunningEventStatus.RECRUITING
                    and e.startAt <= :now then 'CLOSED'
                  else cast(e.status as String) end)
            """)
    Page<RunningEvent> findAllFiltered(@Param("filter") RunningEventListFilter filter,
                                      @Param("now") LocalDateTime now,
                                      @Param("statusName") String statusName, Pageable pageable);

    // 벌크 갱신은 Auditing을 호출하지 않는다. 락 대기 중 저장된 더 최신 수정 시각을 보존한다.
    @Modifying
    @Query("""
            update RunningEvent e
            set e.status = com.runmate.runningevent.entity.RunningEventStatus.CLOSED,
                e.updatedAt = greatest(e.updatedAt, :now)
            where e.status = com.runmate.runningevent.entity.RunningEventStatus.RECRUITING
              and e.startAt <= :now
            """)
    void closeStartedRecruitment(@Param("now") LocalDateTime now);
}
