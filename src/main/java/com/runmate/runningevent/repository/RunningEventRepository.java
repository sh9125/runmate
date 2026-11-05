package com.runmate.runningevent.repository;

import com.runmate.runningevent.entity.RunningEvent;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
public interface RunningEventRepository extends JpaRepository<RunningEvent, Long> {
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
