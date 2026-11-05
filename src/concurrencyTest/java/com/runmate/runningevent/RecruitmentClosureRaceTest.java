package com.runmate.runningevent;

import com.runmate.member.entity.Member;
import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.member.repository.MemberRepository;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventParticipant;
import com.runmate.runningevent.repository.RunningEventParticipantRepository;
import com.runmate.runningevent.repository.RunningEventRepository;
import com.runmate.runningevent.service.RunningEventParticipantService;
import com.runmate.runningevent.service.RunningEventService;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
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
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 첫 트랜잭션을 대기시킨 상태에서 두 번째 트랜잭션의 실제 PostgreSQL 락 대기를 확인한다. */
@SpringBootTest(properties = "logging.level.org.hibernate.SQL=DEBUG")
@ActiveProfiles("concurrency")
@Import(RecruitmentClosureRaceTest.RaceConfiguration.class)
@ContextConfiguration(initializers = ParticipationRaceTest.IsolatedDatabaseGuard.class)
class RecruitmentClosureRaceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime START = LocalDateTime.of(2030, 1, 1, 12, 0);
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();
    private static volatile Gates gates;
    @Autowired RunningEventParticipantService participationService;
    @Autowired RunningEventService eventService;
    @Autowired RunningEventRepository events;
    @Autowired RunningEventParticipantRepository participants;
    @Autowired MemberRepository members;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock testClock;
    private Long eventId;

    @BeforeEach
    void setUp() {
        gates = null;
        testClock.set(START.minusSeconds(1));
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("runmate_concurrency");
        assertThat(jdbc.queryForObject("show transaction_isolation", String.class)).isEqualTo("read committed");
        transactions.executeWithoutResult(tx -> {
            participants.deleteAllInBatch();
            events.deleteAllInBatch();
            members.deleteAllInBatch();
            Member creator = member("creator");
            member("joiner");
            RunningEvent event = events.save(RunningEvent.builder()
                    .title("closure-race").description("baseline").region("서울")
                    .runningPlace("한강").meetingPlace("역").startAt(START)
                    .preferredPaceSeconds(360).targetDistanceKm(BigDecimal.valueOf(5))
                    .capacity(2).creator(creator).build());
            participants.save(RunningEventParticipant.builder().runningEvent(event).member(creator).build());
            eventId = event.getId();
        });
    }

    private Member member(String name) {
        return members.save(Member.builder().email(name + "@closure.test").password("unused")
                .name(name).nickname(name).region("서울").averagePaceSeconds(360)
                .preferredDistanceKm(BigDecimal.valueOf(5)).build());
    }

    @ParameterizedTest(name = "sequential control: {0}")
    @ValueSource(strings = {"detail", "list"})
    void sequentialParticipationThenClosurePreservesBothChanges(String lookup) {
        participationService.participateRunningEvent(eventId, "joiner@closure.test");
        testClock.set(START.plusSeconds(1));
        lookup(lookup);
        assertSnapshot("CLOSED", 2, 2);
        System.out.printf("CONTROL %s: status=CLOSED, stored=2, active=2%n", lookup);
    }

    @Test
    void descendingPageClosesOnlyItsEventsAndPreservesOrder() {
        Long second = anotherEvent("second");
        Long third = anotherEvent("third");
        testClock.set(START.plusSeconds(1));
        var page = eventService.getRunningEvents(PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "id")));
        assertThat(page.getContent()).extracting(dto -> dto.id()).containsExactly(third, second);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).allSatisfy(dto -> {
            assertThat(dto.status().name()).isEqualTo("CLOSED");
            assertThat(dto.currentParticipantCount()).isEqualTo(1);
        });
        assertSnapshot("RECRUITING", 1, 1); // 페이지 밖의 이벤트는 모집이 마감되지 않는다.
        assertThat(eventService.getRunningEvents(PageRequest.of(3, 2)).getContent()).isEmpty();
    }

    @Test
    void overlappingPagesWithOppositeSortsUseConsistentLocks() throws Exception {
        Long second = anotherEvent("second");
        Long third = anotherEvent("third");
        testClock.set(START.plusSeconds(1));
        Gates current = new Gates(true);
        gates = current;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> descending = pool.submit(() -> runAs("lookup", () -> {
                var page = eventService.getRunningEvents(PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "id")));
                assertThat(page.getContent()).extracting(dto -> dto.id()).containsExactly(third, second, eventId);
            }));
            await(current.lookupLoaded);
            Future<?> ascending = pool.submit(() -> {
                var page = eventService.getRunningEvents(PageRequest.of(0, 3, Sort.by("id")));
                assertThat(page.getContent()).extracting(dto -> dto.id()).containsExactly(eventId, second, third);
            });
            awaitDatabaseLockWait();
            current.releaseLookup.countDown();
            descending.get(20, TimeUnit.SECONDS);
            ascending.get(20, TimeUnit.SECONDS);
            assertSnapshot("CLOSED", 1, 1);
            assertThat(jdbc.queryForObject("select count(*) from running_events where status='CLOSED'", Integer.class))
                    .isEqualTo(3);
        } finally {
            current.releaseLookup.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            gates = null;
        }
    }

    @Test
    void lockingOneEventDoesNotBlockADifferentEvent() throws Exception {
        Long second = anotherEvent("independent");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> transactions.executeWithoutResult(tx -> {
                events.findByIdForUpdate(eventId).orElseThrow();
                locked.countDown();
                try { await(release); } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }));
            await(locked);
            var other = pool.submit(() -> eventService.getRunningEvent(second));
            assertThat(other.get(5, TimeUnit.SECONDS).id()).isEqualTo(second);
            release.countDown();
            holder.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Long anotherEvent(String title) {
        return transactions.execute(tx -> {
            Member creator = members.findByEmail("creator@closure.test").orElseThrow();
            RunningEvent event = events.save(RunningEvent.builder()
                    .title(title).description("extra").region("서울").runningPlace("한강").meetingPlace("역")
                    .startAt(START).preferredPaceSeconds(360).targetDistanceKm(BigDecimal.valueOf(5))
                    .capacity(2).creator(creator).build());
            participants.save(RunningEventParticipant.builder().runningEvent(event).member(creator).build());
            return event.getId();
        });
    }

    @ParameterizedTest(name = "overlap: {0}, closure commits first={1}")
    @CsvSource({"detail,true", "detail,false", "list,true", "list,false"})
    void participationAndClosureSerializeWithoutOverwriting(String lookup, boolean closureFirst) throws Exception {
        Gates current = new Gates(closureFirst);
        gates = current;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            if (closureFirst) {
                testClock.set(START.plusSeconds(1));
                Future<?> closing = pool.submit(() -> runAs("lookup", () -> lookup(lookup)));
                await(current.lookupLoaded); // 조회 요청이 이벤트 행의 락을 획득한 상태다.
                Future<?> joining = pool.submit(() -> runAs("join", () ->
                        assertThatThrownBy(() -> participationService.participateRunningEvent(eventId, "joiner@closure.test"))
                                .isInstanceOfSatisfying(BusinessException.class,
                                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EVENT_NOT_RECRUITING))));
                await(current.joinAttempted);
                awaitDatabaseLockWait();
                current.releaseLookup.countDown();
                closing.get(20, TimeUnit.SECONDS);
                joining.get(20, TimeUnit.SECONDS);
                assertSnapshot("CLOSED", 1, 1);
            } else {
                Future<?> joining = pool.submit(() -> runAs("join", () ->
                        participationService.participateRunningEvent(eventId, "joiner@closure.test")));
                await(current.joinInserted); // 시작 전 참가 검사를 통과했고 이벤트 행의 락을 유지 중이다.
                assertSnapshot("RECRUITING", 1, 1);
                testClock.set(START.plusSeconds(1));
                Future<?> closing = pool.submit(() -> runAs("lookup", () -> lookup(lookup)));
                await(current.lookupAttempted);
                awaitDatabaseLockWait();
                current.releaseJoin.countDown();
                joining.get(20, TimeUnit.SECONDS);
                closing.get(20, TimeUnit.SECONDS);
                assertSnapshot("CLOSED", 2, 2);
            }
            System.out.printf("LOCKED %s, closureFirst=%s: PostgreSQL lock wait observed, status=CLOSED, stored=active=%d%n",
                    lookup, closureFirst, closureFirst ? 1 : 2);
        } finally {
            current.releaseJoin.countDown();
            current.releaseLookup.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            gates = null;
        }
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
            Thread.sleep(20); // DB의 대기 상태를 반복 확인하며, 이 시간으로 트랜잭션 순서를 정하지 않는다.
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected an actual PostgreSQL event-row lock wait");
    }

    private void lookup(String kind) {
        if (kind.equals("detail")) {
            eventService.getRunningEvent(eventId);
        } else {
            var page = eventService.getRunningEvents(PageRequest.of(0, 10, Sort.by("id")));
            assertThat(page.getTotalElements()).isEqualTo(1);
        }
    }

    private void assertSnapshot(String status, int stored, int active) {
        assertThat(jdbc.queryForObject("select status from running_events where id = ?", String.class, eventId))
                .isEqualTo(status);
        assertThat(jdbc.queryForObject("select current_participant_count from running_events where id = ?",
                Integer.class, eventId)).isEqualTo(stored);
        assertThat(jdbc.queryForObject("select count(*) from running_event_participants where running_event_id = ? and status = 'PARTICIPATING'",
                Integer.class, eventId)).isEqualTo(active);
    }

    private static void runAs(String role, Runnable action) {
        ROLE.set(role);
        try { action.run(); } finally { ROLE.remove(); }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("controlled interleaving gate").isTrue();
    }

    private static class Gates {
        final boolean closureFirst;
        Gates(boolean closureFirst) { this.closureFirst = closureFirst; }
        final CountDownLatch joinAttempted = new CountDownLatch(1);
        final CountDownLatch lookupAttempted = new CountDownLatch(1);
        final CountDownLatch joinInserted = new CountDownLatch(1);
        final CountDownLatch lookupLoaded = new CountDownLatch(1);
        final CountDownLatch releaseJoin = new CountDownLatch(1);
        final CountDownLatch releaseLookup = new CountDownLatch(1);
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

        @Bean
        static BeanPostProcessor repositoryGates() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    boolean eventRepository = bean instanceof RunningEventRepository;
                    boolean participantRepository = bean instanceof RunningEventParticipantRepository;
                    if (!eventRepository && !participantRepository) return bean;
                    ProxyFactory proxy = new ProxyFactory(bean);
                    proxy.addAdvice((MethodInterceptor) invocation -> {
                        Gates current = gates;
                        if (current == null) return invocation.proceed();
                        String method = invocation.getMethod().getName();
                        boolean lookup = eventRepository && "lookup".equals(ROLE.get())
                                && (method.equals("findByIdForUpdate") || method.equals("findPageForUpdate"));
                        if (lookup) current.lookupAttempted.countDown();
                        if (eventRepository && "join".equals(ROLE.get()) && method.equals("findByIdForUpdate")) {
                            current.joinAttempted.countDown();
                        }
                        Object result = invocation.proceed();
                        if (participantRepository && "join".equals(ROLE.get()) && method.equals("save") && !current.closureFirst) {
                            current.joinInserted.countDown();
                            await(current.releaseJoin);
                        } else if (lookup && current.closureFirst) {
                            current.lookupLoaded.countDown();
                            await(current.releaseLookup);
                        }
                        return result;
                    });
                    return proxy.getProxy();
                }
            };
        }
    }
}
