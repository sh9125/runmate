package com.runmate.runningevent.repository;

import com.runmate.runningevent.entity.RunningEvent;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    @Query(value = "select e.id from RunningEvent e", countQuery = "select count(e) from RunningEvent e")
    Page<Long> findIds(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from RunningEvent e where e.id in :ids order by e.id asc")
    List<RunningEvent> findPageEventsForUpdate(@Param("ids") List<Long> ids);

    // 이 메서드는 서비스의 쓰기 가능한 트랜잭션 안에서 호출한다.
    // 락을 얻기 전에 옛 이벤트 데이터가 영속성 컨텍스트에 들어오지 않도록 ID만 먼저 조회한다.
    default Page<RunningEvent> findPageForUpdate(Pageable pageable) {
        // 사용자가 요청한 페이지와 정렬 순서를 기억한다. 예: [30, 20, 10]
        Page<Long> ids = findIds(pageable);
        if (ids.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, ids.getTotalElements());
        }

        // 쿼리의 ID 오름차순 정렬에 따라 [10, 20, 30] 순서로 잠그며 최신 이벤트를 조회한다.
        // 조회 결과를 '이벤트 ID → 이벤트 객체' 형태의 Map으로 만들어 ID로 찾을 수 있게 한다.
        Map<Long, RunningEvent> locked = findPageEventsForUpdate(ids.getContent()).stream()
                .collect(Collectors.toMap(RunningEvent::getId, Function.identity()));

        // 원래 ID 순서 [30, 20, 10]를 따라 Map에서 이벤트를 꺼내 응답 순서를 복원한다.
        // Page.map은 페이지 번호와 전체 개수도 유지하면서 ID를 이벤트 객체로 바꾼다.
        return ids.map(id -> {
            RunningEvent event = locked.get(id);
            // ID 조회 후 실제 이벤트가 사라졌다면 조용히 누락시키지 않고 실패시킨다.
            if (event == null) {
                throw new EntityNotFoundException("목록 조회 중 대상 러닝 이벤트가 사라졌습니다.");
            }
            return event;
        });
    }
}
