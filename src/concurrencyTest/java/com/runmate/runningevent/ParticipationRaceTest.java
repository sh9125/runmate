package com.runmate.runningevent;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventParticipant;
import com.runmate.runningevent.dto.request.RunningEventUpdateRequestDto;
import com.runmate.runningevent.repository.RunningEventParticipantRepository;
import com.runmate.runningevent.repository.RunningEventRepository;
import com.runmate.runningevent.service.RunningEventParticipantService;
import com.runmate.runningevent.service.RunningEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** 이벤트 행의 변경이 순서대로 처리되는지 실제 PostgreSQL에서 검증하는 회귀 테스트. */
@SpringBootTest
@ActiveProfiles("concurrency")
@ContextConfiguration(initializers = ParticipationRaceTest.IsolatedDatabaseGuard.class)
class ParticipationRaceTest {
    @Autowired RunningEventParticipantService service;
    @Autowired RunningEventService eventService;
    @Autowired RunningEventRepository events;
    @Autowired RunningEventParticipantRepository participants;
    @Autowired MemberRepository members;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    private Long eventId;
    private String firstEmail;
    private String secondEmail;
    private LocalDateTime startAt;

    @BeforeEach
    void setUp() {
        // 외부 설정으로 다른 DB에 연결됐다면 데이터를 정리하기 전에 테스트를 중단한다.
        assertThat(jdbc.queryForObject("select current_database()", String.class))
                .isEqualTo("runmate_concurrency");
        assertThat(jdbc.queryForObject("show transaction_isolation", String.class))
                .isEqualTo("read committed");
        transactions.executeWithoutResult(tx -> {
            participants.deleteAllInBatch();
            events.deleteAllInBatch();
            members.deleteAllInBatch();
            Member creator = member("creator");
            firstEmail = member("first").getEmail();
            secondEmail = member("second").getEmail();
            // PostgreSQL timestamp(6)은 나노초를 반올림하므로 테스트 시각을 초 단위로 맞춘다.
            startAt = LocalDateTime.now().plusDays(1).withNano(0);
            RunningEvent event = events.save(RunningEvent.builder()
                    .title("race").description("baseline").region("서울")
                    .runningPlace("한강").meetingPlace("역")
                    .startAt(startAt)
                    .preferredPaceSeconds(360).targetDistanceKm(BigDecimal.valueOf(5))
                    .capacity(2).creator(creator).build());
            participants.save(RunningEventParticipant.builder().runningEvent(event).member(creator).build());
            eventId = event.getId();
        });
    }

    private Member member(String name) {
        return members.save(Member.builder().email(name + "@race.test").password("unused")
                .name(name).nickname(name).region("서울").averagePaceSeconds(360)
                .preferredDistanceKm(BigDecimal.valueOf(5)).build());
    }

    @Test
    void sequentialControlRejectsSecondMember() {
        service.participateRunningEvent(eventId, firstEmail);
        assertThatThrownBy(() -> service.participateRunningEvent(eventId, secondEmail))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EVENT_CAPACITY_FULL));
        assertSnapshot(2, 2);
    }

    @Test
    void lastSeatRaceAllowsExactlyOneParticipant() throws Exception {
        List<Outcome> outcomes = race(firstEmail, secondEmail);
        assertSingleRejection(outcomes, ErrorCode.EVENT_CAPACITY_FULL);
        assertSnapshot(2, 2);
        System.out.println("LOCKED last-seat: committed=1, business-rejected=1, stored=2, active=2");
    }

    @Test
    void sameMemberRaceReturnsBusinessRejectionWithoutConstraintFailure() throws Exception {
        List<Outcome> outcomes = race(firstEmail, firstEmail);
        assertSingleRejection(outcomes, ErrorCode.ALREADY_PARTICIPATING);
        assertSnapshot(2, 2);
        System.out.println("LOCKED same-member: committed=1, ALREADY_PARTICIPATING=1, stored=2, active=2");
    }

    @Test
    void duplicateCancellationDecrementsOnlyOnce() throws Exception {
        service.participateRunningEvent(eventId, firstEmail);
        List<Outcome> outcomes = race(() -> service.cancelParticipation(eventId, firstEmail),
                () -> service.cancelParticipation(eventId, firstEmail));
        assertSingleRejection(outcomes, ErrorCode.NOT_PARTICIPATING);
        assertSnapshot(1, 1);
    }

    @Test
    void duplicateRejoiningReusesTheSameRecord() throws Exception {
        Long participantId = service.participateRunningEvent(eventId, firstEmail).id();
        service.cancelParticipation(eventId, firstEmail);
        assertSingleRejection(race(firstEmail, firstEmail), ErrorCode.ALREADY_PARTICIPATING);
        assertSnapshot(2, 2);
        assertThat(jdbc.queryForObject("select id from running_event_participants where running_event_id=? and member_id=(select id from members where email=?)",
                Long.class, eventId, firstEmail)).isEqualTo(participantId);
    }

    @Test
    void rejoiningAndNewParticipationCompeteForTheSameSeat() throws Exception {
        service.participateRunningEvent(eventId, firstEmail);
        service.cancelParticipation(eventId, firstEmail);
        assertSingleRejection(race(firstEmail, secondEmail), ErrorCode.EVENT_CAPACITY_FULL);
        assertSnapshot(2, 2);
    }

    @Test
    void cancellationAndParticipationPreserveCountAndRecords() throws Exception {
        service.participateRunningEvent(eventId, firstEmail);
        List<Outcome> outcomes = race(() -> service.cancelParticipation(eventId, firstEmail),
                () -> service.participateRunningEvent(eventId, secondEmail));
        assertThat(outcomes.get(0).failure()).isNull();
        assertOptionalRejection(outcomes.get(1), ErrorCode.EVENT_CAPACITY_FULL);
        int expected = outcomes.get(1).failure() == null ? 2 : 1;
        assertSnapshot(expected, expected);
        assertThat(jdbc.queryForObject("select status from running_event_participants where running_event_id=? and member_id=(select id from members where email=?)",
                String.class, eventId, firstEmail)).isEqualTo("CANCELED");
    }

    @Test
    void eventCancellationAndParticipationPreserveTheCommittedOrder() throws Exception {
        List<Outcome> outcomes = race(() -> eventService.cancelRunningEvent(eventId, "creator@race.test"),
                () -> service.participateRunningEvent(eventId, firstEmail));
        assertThat(outcomes.get(0).failure()).isNull();
        assertOptionalRejection(outcomes.get(1), ErrorCode.EVENT_CANCELED);
        int expected = outcomes.get(1).failure() == null ? 2 : 1;
        assertSnapshot(expected, expected);
        assertThat(jdbc.queryForObject("select status from running_events where id=?", String.class, eventId))
                .isEqualTo("CANCELED");
    }

    @Test
    void capacityReductionAndParticipationCannotBothSucceed() throws Exception {
        List<Outcome> outcomes = race(() -> eventService.updateRunningEvent(eventId, update(startAt, 1), "creator@race.test"),
                () -> service.participateRunningEvent(eventId, firstEmail));
        assertThat(outcomes.stream().filter(o -> o.failure() == null).count()).isEqualTo(1);
        assertOptionalRejection(outcomes.get(0), ErrorCode.CAPACITY_BELOW_PARTICIPANTS);
        assertOptionalRejection(outcomes.get(1), ErrorCode.EVENT_CAPACITY_FULL);
        int expected = outcomes.get(0).failure() == null ? 1 : 2;
        assertSnapshot(expected, expected);
        assertThat(jdbc.queryForObject("select capacity from running_events where id=?", Integer.class, eventId))
                .isEqualTo(expected);
    }

    @Test
    void dateChangeAndParticipationRespectDetailsRestriction() throws Exception {
        List<Outcome> outcomes = race(() -> eventService.updateRunningEvent(eventId, update(startAt.plusDays(1), 2), "creator@race.test"),
                () -> service.participateRunningEvent(eventId, firstEmail));
        assertThat(outcomes.get(1).failure()).isNull();
        assertOptionalRejection(outcomes.get(0), ErrorCode.EVENT_DETAILS_LOCKED);
        assertSnapshot(2, 2);
        assertThat(jdbc.queryForObject("select start_at from running_events where id=?", LocalDateTime.class, eventId))
                .isEqualTo(outcomes.get(0).failure() == null ? startAt.plusDays(1) : startAt);
    }

    private RunningEventUpdateRequestDto update(LocalDateTime start, int capacity) {
        return new RunningEventUpdateRequestDto("race", "baseline", "서울", "한강", "역", start,
                360, BigDecimal.valueOf(5), capacity);
    }

    private void assertOptionalRejection(Outcome outcome, ErrorCode expected) {
        if (outcome.failure() != null) {
            assertThat(outcome.failure()).isInstanceOfSatisfying(BusinessException.class,
                    ex -> assertThat(ex.getErrorCode()).isEqualTo(expected));
        }
    }

    private void assertSingleRejection(List<Outcome> outcomes, ErrorCode expected) {
        assertThat(outcomes.stream().filter(o -> o.failure() == null).count()).isEqualTo(1);
        Throwable failure = outcomes.stream().map(Outcome::failure).filter(f -> f != null).findFirst().orElseThrow();
        assertThat(failure).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(expected));
    }

    private List<Outcome> race(String first, String second) throws Exception {
        return race(() -> service.participateRunningEvent(eventId, first),
                () -> service.participateRunningEvent(eventId, second));
    }

    private List<Outcome> race(Runnable first, Runnable second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> a = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return execute(first); });
            Future<Outcome> b = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return execute(second); });
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Outcome execute(Runnable action) {
        try {
            // 스프링 트랜잭션 프록시는 커밋에 성공한 뒤 호출 결과를 반환한다.
            action.run();
            return new Outcome(null);
        } catch (RuntimeException ex) {
            return new Outcome(ex);
        }
    }

    private void assertSnapshot(int stored, int active) {
        assertThat(jdbc.queryForObject("select current_participant_count from running_events where id = ?",
                Integer.class, eventId)).isEqualTo(stored);
        assertThat(jdbc.queryForObject("select count(*) from running_event_participants where running_event_id = ? and status = 'PARTICIPATING'",
                Integer.class, eventId)).isEqualTo(active);
        assertThat(jdbc.queryForObject("select count(*) from (select member_id from running_event_participants where running_event_id = ? group by member_id having count(*) > 1) duplicates",
                Integer.class, eventId)).isZero();
    }

    private record Outcome(Throwable failure) { }

    static class IsolatedDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            // EntityManagerFactory 생성과 Hibernate의 테이블 생성·삭제 작업 전에 실행된다.
            String url = context.getEnvironment().getProperty("spring.datasource.url");
            if (!"jdbc:postgresql://localhost:5434/runmate_concurrency".equals(url)) {
                throw new IllegalStateException("Concurrency experiments require the isolated database URL");
            }
        }
    }

}
