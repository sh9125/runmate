package com.runmate.runningevent;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import com.runmate.runningevent.dto.request.RunningEventListFilter;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventParticipant;
import com.runmate.runningevent.entity.RunningEventStatus;
import com.runmate.runningevent.repository.RunningEventParticipantRepository;
import com.runmate.runningevent.repository.RunningEventRepository;
import com.runmate.runningevent.service.RecruitmentClosureJob;
import com.runmate.runningevent.service.RunningEventParticipantService;
import com.runmate.runningevent.service.RunningEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("concurrency")
@Import(RecruitmentClosureRaceTest.RaceConfiguration.class)
@ContextConfiguration(initializers = ParticipationRaceTest.IsolatedDatabaseGuard.class)
class RecruitmentClosureRaceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime START = LocalDateTime.of(2030, 1, 1, 12, 0);
    private static final String CREATOR_EMAIL = "creator@closure.test";
    private static final String JOINER_EMAIL = "joiner@closure.test";

    @Autowired RunningEventParticipantService participationService;
    @Autowired RunningEventService eventService;
    @Autowired RecruitmentClosureJob closureJob;
    @Autowired RunningEventRepository events;
    @Autowired RunningEventParticipantRepository participants;
    @Autowired MemberRepository members;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock testClock;
    private Long eventId;

    @BeforeEach
    void setUp() {
        testClock.set(START.minusSeconds(1));
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("runmate_concurrency");
        assertThat(jdbc.queryForObject("show transaction_isolation", String.class)).isEqualTo("read committed");
        transactions.executeWithoutResult(tx -> {
            jdbc.update("delete from running_event_waitlist_entries");
            participants.deleteAllInBatch();
            events.deleteAllInBatch();
            members.deleteAllInBatch();
            member("creator");
            member("joiner");
            eventId = createEvent("closure-race", START);
        });
    }

    @Test
    void jobClosesOnlyDueRecruitingEventsWithoutAReadRequest() {
        Long earlier = createEvent("earlier", START.minusMinutes(1));
        Long future = createEvent("future", START.plusSeconds(1));
        Long canceled = createEvent("canceled", START);
        Long completed = createEvent("completed", START);
        jdbc.update("update running_events set status = 'CANCELED' where id = ?", canceled);
        jdbc.update("update running_events set status = 'COMPLETED' where id = ?", completed);

        testClock.set(START);
        closureJob.closeStartedRecruitment();

        assertSnapshot("CLOSED", 1, 1);
        assertThat(statusOf(earlier)).isEqualTo("CLOSED");
        assertThat(statusOf(future)).isEqualTo("RECRUITING");
        assertThat(statusOf(canceled)).isEqualTo("CANCELED");
        assertThat(statusOf(completed)).isEqualTo("COMPLETED");
        assertThat(updatedAt()).isEqualTo(START);

        testClock.set(START.plusSeconds(1));
        closureJob.closeStartedRecruitment();
        assertThat(statusOf(future)).isEqualTo("CLOSED");
        assertThat(updatedAt()).isEqualTo(START);
    }

    @Test
    void descendingPageShowsClosureBeforeJobAndPreservesOrder() {
        Long second = createEvent("second", START);
        Long third = createEvent("third", START);
        testClock.set(START);
        var page = eventService.getRunningEvents(PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "id")));
        assertThat(page.getContent()).extracting(dto -> dto.id()).containsExactly(third, second);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).allSatisfy(dto -> {
            assertThat(dto.status()).isEqualTo(RunningEventStatus.CLOSED);
            assertThat(dto.currentParticipantCount()).isEqualTo(1);
        });
        assertThat(statusOf(second)).isEqualTo("RECRUITING");
        assertThat(statusOf(third)).isEqualTo("RECRUITING");
        assertThat(eventService.getRunningEvents(PageRequest.of(3, 2)).getContent()).isEmpty();
    }

    @Test
    void filtersAndCountsUseEffectiveStatusBeforeJob() {
        Long future = createEvent("future", START.plusSeconds(1));
        testClock.set(START);
        var pageRequest = PageRequest.of(0, 1, Sort.by("id"));
        var closed = new RunningEventListFilter(null, null, null, null, null, null, RunningEventStatus.CLOSED);
        var recruiting = new RunningEventListFilter(null, null, null, null, null, null, RunningEventStatus.RECRUITING);

        var closedPage = eventService.getRunningEvents(pageRequest, closed);
        assertThat(closedPage.getTotalElements()).isEqualTo(1);
        assertThat(closedPage.getContent()).singleElement().satisfies(dto -> {
            assertThat(dto.id()).isEqualTo(eventId);
            assertThat(dto.status()).isEqualTo(RunningEventStatus.CLOSED);
        });
        var recruitingPage = eventService.getRunningEvents(pageRequest, recruiting);
        assertThat(recruitingPage.getTotalElements()).isEqualTo(1);
        assertThat(recruitingPage.getContent()).extracting(dto -> dto.id()).containsExactly(future);
        assertThat(statusOf(eventId)).isEqualTo("RECRUITING");
    }

    @Test
    void readsSameEventWhileParticipationTransactionHoldsItsLock() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> holdAfter(
                    () -> participationService.participateRunningEvent(eventId, JOINER_EMAIL), locked, release));
            await(locked);
            testClock.set(START);
            var reader = pool.submit(() -> {
                var detail = eventService.getRunningEvent(eventId, CREATOR_EMAIL);
                assertThat(detail.status()).isEqualTo(RunningEventStatus.CLOSED);
                assertThat(detail.currentParticipantCount()).isEqualTo(1);
                assertThat(detail.participantNicknames()).containsExactly("creator");
                var list = eventService.getRunningEvents(PageRequest.of(0, 10));
                assertThat(list.getContent()).singleElement()
                        .satisfies(dto -> assertThat(dto.status()).isEqualTo(RunningEventStatus.CLOSED));
            });
            reader.get(5, TimeUnit.SECONDS);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertSnapshot("RECRUITING", 2, 2);
        } finally {
            release.countDown();
            shutdown(pool);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void participationAndScheduledClosurePreserveBothRules(boolean closureFirst) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            if (closureFirst) testClock.set(START);
            Runnable join = () -> participationService.participateRunningEvent(eventId, JOINER_EMAIL);
            Runnable close = closureJob::closeStartedRecruitment;
            Future<?> first = pool.submit(() -> holdAfter(closureFirst ? close : join, locked, release));
            await(locked);
            testClock.set(START);
            Future<?> second = pool.submit(() -> {
                if (closureFirst) {
                    assertThatThrownBy(join::run).isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EVENT_NOT_RECRUITING));
                } else {
                    close.run();
                }
            });
            awaitDatabaseLockWait();
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            int count = closureFirst ? 1 : 2;
            assertSnapshot("CLOSED", count, count);
        } finally {
            release.countDown();
            shutdown(pool);
        }
    }

    private void holdAfter(Runnable work, CountDownLatch locked, CountDownLatch release) {
        transactions.executeWithoutResult(tx -> {
            work.run();
            locked.countDown();
            try {
                await(release);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        });
    }

    private Long createEvent(String title, LocalDateTime start) {
        return transactions.execute(tx -> {
            Member creator = members.findByEmail(CREATOR_EMAIL).orElseThrow();
            RunningEvent event = events.save(RunningEvent.builder()
                    .title(title).description("closure fixture").region("서울")
                    .runningPlace("한강").meetingPlace("역").startAt(start)
                    .preferredPaceSeconds(360).targetDistanceKm(BigDecimal.valueOf(5))
                    .capacity(2).creator(creator).build());
            participants.save(RunningEventParticipant.builder().runningEvent(event).member(creator).build());
            return event.getId();
        });
    }

    private void member(String name) {
        members.save(Member.builder().email(name + "@closure.test").password("unused")
                .name(name).nickname(name).region("서울").averagePaceSeconds(360)
                .preferredDistanceKm(BigDecimal.valueOf(5)).build());
    }

    private String statusOf(Long id) {
        return jdbc.queryForObject("select status from running_events where id = ?", String.class, id);
    }

    private LocalDateTime updatedAt() {
        return jdbc.queryForObject("select updated_at from running_events where id = ?", LocalDateTime.class, eventId);
    }

    private void assertSnapshot(String status, int stored, int active) {
        assertThat(statusOf(eventId)).isEqualTo(status);
        assertThat(jdbc.queryForObject("select current_participant_count from running_events where id = ?",
                Integer.class, eventId)).isEqualTo(stored);
        assertThat(jdbc.queryForObject("""
                select count(*) from running_event_participants
                where running_event_id = ? and status = 'PARTICIPATING'
                """, Integer.class, eventId)).isEqualTo(active);
    }

    private void awaitDatabaseLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            Boolean waiting = jdbc.queryForObject("""
                    select exists(select 1 from pg_stat_activity
                    where datname = current_database() and pid <> pg_backend_pid()
                      and wait_event_type = 'Lock' and query like '%running_events%')
                    """, Boolean.class);
            if (Boolean.TRUE.equals(waiting)) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected an actual PostgreSQL event-row lock wait");
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("controlled interleaving gate").isTrue();
    }

    private static void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> value = new AtomicReference<>();
        void set(LocalDateTime time) { value.set(time.atZone(ZONE).toInstant()); }
        @Override public ZoneId getZone() { return ZONE; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return value.get(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RaceConfiguration {
        @Bean @Primary
        MutableClock boundaryClock() { return new MutableClock(); }
    }
}
