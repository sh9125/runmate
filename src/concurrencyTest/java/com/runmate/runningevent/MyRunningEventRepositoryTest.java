package com.runmate.runningevent;

import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventParticipant;
import com.runmate.runningevent.entity.RunningEventStatus;
import com.runmate.runningevent.entity.RunningEventWaitlistEntry;
import com.runmate.runningevent.repository.RunningEventParticipantRepository;
import com.runmate.runningevent.repository.RunningEventRepository;
import com.runmate.runningevent.repository.RunningEventWaitlistRepository;
import com.runmate.runningevent.service.MyRunningEventService;
import com.runmate.runningevent.service.RunningEventService;
import com.runmate.runningevent.service.RunningEventParticipantService;
import com.runmate.runningevent.dto.request.RunningEventUpdateRequestDto;
import com.runmate.runningevent.dto.request.RunningEventListFilter;
import com.runmate.runningevent.dto.response.MyRunningEventApplicationStatus;
import com.runmate.runningevent.dto.response.MyRunningEventEndReason;
import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 격리 PostgreSQL에서 목록 분류와 실제 count 쿼리를 확인한다. 경합 테스트는 아니다. */
@SpringBootTest
@ActiveProfiles("concurrency")
@ContextConfiguration(initializers = ParticipationRaceTest.IsolatedDatabaseGuard.class)
@Import(MyRunningEventRepositoryTest.FixedTimeConfig.class)
@Transactional(isolation = Isolation.REPEATABLE_READ)
class MyRunningEventRepositoryTest {
    @Autowired RunningEventRepository events;
    @Autowired RunningEventParticipantRepository participants;
    @Autowired RunningEventWaitlistRepository waitlist;
    @Autowired MemberRepository members;
    @Autowired MyRunningEventService myEvents;
    @Autowired RunningEventService eventService;
    @Autowired RunningEventParticipantService participationService;

    private final LocalDateTime now = LocalDateTime.of(2026, 11, 7, 12, 0);
    private final PageRequest page = PageRequest.of(0, 20, Sort.by("id"));
    private Member creator;
    private Member viewer;

    @TestConfiguration
    static class FixedTimeConfig {
        @Bean
        @Primary
        Clock mypageTestClock() {
            ZoneId zone = ZoneId.of("Asia/Seoul");
            return Clock.fixed(LocalDateTime.of(2026, 11, 7, 12, 0).atZone(zone).toInstant(), zone);
        }
    }

    @BeforeEach
    void setUp() {
        creator = member();
        viewer = member();
    }

    @Test
    void filtersBeforePaginationAndKeepsCreationOrder() {
        RunningEvent older = event(now.plusDays(2));
        RunningEvent newer = event(now.plusDays(1));
        event(now.plusDays(3));
        events.save(RunningEvent.builder().title("마이페이지").description("조회 테스트")
                .region("부산").runningPlace("한강").meetingPlace("역").startAt(now.plusDays(1))
                .preferredPaceSeconds(390).targetDistanceKm(BigDecimal.TEN)
                .capacity(10).creator(creator).build());
        var sort = Sort.by(Sort.Direction.DESC, "createdAt", "id");
        var filter = new RunningEventListFilter("한강", " 서울 ", 360, 390, BigDecimal.valueOf(5), BigDecimal.TEN, null);

        var first = eventService.getRunningEvents(PageRequest.of(0, 2, sort), filter);
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getContent().get(1).id()).isEqualTo(newer.getId());
        assertThat(eventService.getRunningEvents(PageRequest.of(1, 2, sort), filter).getContent())
                .singleElement().satisfies(result -> assertThat(result.id()).isEqualTo(older.getId()));
        assertThat(eventService.getRunningEvents(PageRequest.of(0, 2, sort),
                new RunningEventListFilter("%", null, null, null, null, null, null))).isEmpty();
    }

    @Test
    void statusFilterClassifiesStartedEventsBeforePagination() {
        RunningEvent recruiting = event(now.plusDays(1));
        RunningEvent started = event(now);
        RunningEvent canceled = event(now.plusDays(2));
        canceled.cancel(now);

        var openPage = eventService.getRunningEvents(PageRequest.of(0, 1),
                new RunningEventListFilter(null, null, null, null, null, null, RunningEventStatus.RECRUITING));
        assertThat(openPage.getTotalElements()).isEqualTo(1);
        assertThat(openPage.getContent()).singleElement().satisfies(result -> assertThat(result.id()).isEqualTo(recruiting.getId()));
        var closedPage = eventService.getRunningEvents(page,
                new RunningEventListFilter(null, null, null, null, null, null, RunningEventStatus.CLOSED));
        assertThat(closedPage.getContent()).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo(started.getId());
            assertThat(result.status()).isEqualTo(RunningEventStatus.CLOSED);
        });
        var canceledPage = eventService.getRunningEvents(page,
                new RunningEventListFilter(null, null, null, null, null, null, RunningEventStatus.CANCELED));
        assertThat(canceledPage.getContent()).singleElement().satisfies(result -> assertThat(result.id()).isEqualTo(canceled.getId()));
    }

    @Test
    void nonOwnerCannotCompleteStartedEvent() {
        RunningEvent event = event(now);

        assertThatThrownBy(() -> eventService.completeRunningEvent(event.getId(), viewer.getEmail()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_EVENT_CREATOR));
        assertThat(event.getStatus()).isEqualTo(RunningEventStatus.RECRUITING);
    }

    @Test
    void completionPreservesParticipantHistoryAndAppearsInCompletedFilter() {
        RunningEvent event = event(now);
        participants.save(RunningEventParticipant.builder().runningEvent(event).member(creator).build());
        participate(event);

        var response = eventService.completeRunningEvent(event.getId(), creator.getEmail());
        assertThat(response.status()).isEqualTo(RunningEventStatus.COMPLETED);
        assertThat(response.currentParticipantCount()).isEqualTo(2);
        assertThat(response.participantNicknames()).containsExactly(creator.getNickname(), viewer.getNickname());
        assertThat(myEvents.getParticipatingEvents(viewer.getEmail(), page).getContent())
                .singleElement().satisfies(item -> assertThat(item.event().id()).isEqualTo(event.getId()));
        var completed = new RunningEventListFilter(null, null, null, null, null, null, RunningEventStatus.COMPLETED);
        assertThat(eventService.getRunningEvents(page, completed).getContent())
                .singleElement().satisfies(item -> assertThat(item.id()).isEqualTo(event.getId()));

        assertThatThrownBy(() -> eventService.completeRunningEvent(event.getId(), creator.getEmail()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.EVENT_NOT_COMPLETABLE));
    }

    @Test
    void futureAndCanceledEventsCannotBeCompleted() {
        RunningEvent future = event(now.plusDays(1));
        RunningEvent canceled = event(now.minusMinutes(1));
        canceled.cancel(now.minusDays(1));

        for (RunningEvent event : java.util.List.of(future, canceled)) {
            assertThatThrownBy(() -> eventService.completeRunningEvent(event.getId(), creator.getEmail()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.EVENT_NOT_COMPLETABLE));
        }
        assertThat(future.getStatus()).isEqualTo(RunningEventStatus.RECRUITING);
        assertThat(canceled.getStatus()).isEqualTo(RunningEventStatus.CANCELED);
    }

    @Test
    void classifiesCurrentStateWithoutDuplicatingOldRecords() {
        RunningEvent joined = event(now.plusDays(1));
        participate(joined);
        // 취소된 대기 기록이 남아 있어도 현재 참가 상태가 우선한다.
        waiting(joined).cancel(now);
        RunningEvent waitingAgain = event(now.plusDays(1));
        participate(waitingAgain).cancel(now);
        waiting(waitingAgain);
        RunningEvent canceledWait = event(now.plusDays(1));
        waiting(canceledWait).cancel(now);
        RunningEvent canceledParticipation = event(now.plusDays(1));
        participate(canceledParticipation).cancel(now);
        RunningEvent canceledEvent = event(now.plusDays(1));
        participate(canceledEvent);
        canceledEvent.cancel(now);
        RunningEvent past = event(now.minusDays(1));
        participate(past);
        event(now.plusDays(1)); // 내 신청 기록이 없는 이벤트

        assertThat(events.findMyParticipatingEvents(viewer.getId(), page).getContent())
                .extracting(RunningEvent::getId).containsExactly(joined.getId(), past.getId());
        assertThat(events.findMyWaitingEvents(viewer.getId(), now.plusMinutes(10), page).getContent())
                .extracting(RunningEvent::getId).containsExactly(waitingAgain.getId());
        assertThat(events.findMyEndedEvents(viewer.getId(), now.plusMinutes(10), page).getContent())
                .extracting(RunningEvent::getId)
                .containsExactly(canceledWait.getId(), canceledParticipation.getId(), canceledEvent.getId());
    }

    @Test
    void waitlistClosesExactlyTenMinutesBeforeStart() {
        RunningEvent boundary = event(now.plusMinutes(10));
        waiting(boundary);
        RunningEvent open = event(now.plusMinutes(10).plusSeconds(1));
        waiting(open);
        RunningEvent canceled = event(now.plusDays(1));
        waiting(canceled);
        canceled.cancel(now);
        RunningEvent closed = event(now.minusDays(1));
        waiting(closed);
        closed.closeRecruitmentIfStarted(now);

        assertThat(events.findMyWaitingEvents(viewer.getId(), now.plusMinutes(10), page).getContent())
                .extracting(RunningEvent::getId).containsExactly(open.getId());
        assertThat(events.findMyEndedEvents(viewer.getId(), now.plusMinutes(10), page).getContent())
                .extracting(RunningEvent::getId).containsExactly(boundary.getId(), canceled.getId(), closed.getId());
    }

    @Test
    void paginationCountsEventsAfterClassification() {
        for (int i = 0; i < 3; i++) {
            RunningEvent event = event(now.plusDays(1));
            participate(event).cancel(now);
            waiting(event).cancel(now); // 참가·대기 두 행이 있어도 종료 이벤트는 한 건
        }
        for (int i = 0; i < 3; i++) {
            participate(event(now.plusDays(1)));
            waiting(event(now.plusDays(1)));
        }
        PageRequest secondPage = PageRequest.of(1, 1, Sort.by("id"));
        var ended = events.findMyEndedEvents(viewer.getId(), now.plusMinutes(10), secondPage);
        assertThat(ended.getContent()).hasSize(1);
        assertThat(ended.getTotalElements()).isEqualTo(3);
        assertThat(events.findMyParticipatingEvents(viewer.getId(), secondPage).getTotalElements()).isEqualTo(3);
        assertThat(events.findMyWaitingEvents(viewer.getId(), now.plusMinutes(10), secondPage).getTotalElements()).isEqualTo(3);
        assertThat(events.findByCreator_Id(creator.getId(), secondPage).getTotalElements()).isEqualTo(9);
        assertThat(events.findByCreator_Id(viewer.getId(), secondPage).getTotalElements()).isZero();
    }

    @Test
    void serviceReturnsBatchRanksAndCreatorNickname() {
        RunningEvent first = event(now.plusDays(1));
        // 먼저 신청한 유효 대기자만 순위에 포함한다.
        waitlist.save(RunningEventWaitlistEntry.builder().runningEvent(first).member(creator)
                .queueNumber(1).requestedAt(now.minusHours(2)).build());
        RunningEventWaitlistEntry canceled = waitlist.save(RunningEventWaitlistEntry.builder()
                .runningEvent(first).member(member()).queueNumber(2).requestedAt(now.minusHours(2)).build());
        canceled.cancel(now.minusHours(1));
        waitlist.save(RunningEventWaitlistEntry.builder().runningEvent(first).member(viewer)
                .queueNumber(3).requestedAt(now.minusHours(1)).build());
        RunningEvent second = event(now.plusDays(1));
        waiting(second);

        var result = myEvents.getWaitingEvents(viewer.getEmail(), page);
        assertThat(result.getContent()).extracting(r -> r.waitlistPosition()).containsExactly(2L, 1L);
        assertThat(result.getContent()).allSatisfy(r -> {
            assertThat(r.applicationStatus()).isEqualTo(MyRunningEventApplicationStatus.WAITING);
            assertThat(r.endReason()).isNull();
            assertThat(r.event().creatorNickname()).isEqualTo(creator.getNickname());
        });
    }

    @Test
    void serviceDistinguishesEndReasonsAndMostRecentCancellation() {
        participate(event(now.plusDays(1))).cancel(now.minusMinutes(2));
        waiting(event(now.plusDays(1))).cancel(now.minusMinutes(1));
        waiting(event(now.plusMinutes(10)));
        RunningEvent canceledEvent = event(now.plusDays(1));
        participate(canceledEvent);
        canceledEvent.cancel(now);
        RunningEvent participantLast = event(now.plusDays(1));
        waiting(participantLast).cancel(now.minusMinutes(2));
        participate(participantLast).cancel(now.minusMinutes(1));
        RunningEvent waitlistLast = event(now.plusDays(1));
        participate(waitlistLast).cancel(now.minusMinutes(2));
        waiting(waitlistLast).cancel(now.minusMinutes(1));
        RunningEvent legacy = event(now.plusDays(1));
        RunningEventParticipant oldParticipant = participate(legacy);
        oldParticipant.cancel(now.minusMinutes(2));
        org.springframework.test.util.ReflectionTestUtils.setField(oldParticipant, "canceledAt", null);
        waiting(legacy).cancel(now.minusMinutes(1));

        var result = myEvents.getEndedEvents(viewer.getEmail(), page);
        assertThat(result.getContent()).extracting(r -> r.endReason()).containsExactly(
                MyRunningEventEndReason.PARTICIPATION_CANCELED,
                MyRunningEventEndReason.WAITLIST_CANCELED,
                MyRunningEventEndReason.WAITLIST_CLOSED,
                MyRunningEventEndReason.EVENT_CANCELED,
                MyRunningEventEndReason.PARTICIPATION_CANCELED,
                MyRunningEventEndReason.WAITLIST_CANCELED,
                MyRunningEventEndReason.DIRECT_CANCELED);
        assertThat(result.getContent()).allSatisfy(r -> {
            assertThat(r.applicationStatus()).isEqualTo(MyRunningEventApplicationStatus.ENDED);
            assertThat(r.waitlistPosition()).isNull();
        });
    }

    @Test
    void reapplicationMovesBetweenListsAndClearsCancellationTime() {
        RunningEvent event = event(now.plusDays(1));
        RunningEventParticipant participant = participate(event);
        participant.cancel(now.minusMinutes(3));
        assertThat(participant.getCanceledAt()).isEqualTo(now.minusMinutes(3));
        RunningEventWaitlistEntry entry = waiting(event);
        entry.cancel(now.minusMinutes(2));
        assertThat(entry.getCanceledAt()).isEqualTo(now.minusMinutes(2));
        entry.reapply(2, now.minusMinutes(1));
        assertThat(entry.getCanceledAt()).isNull();
        assertThat(myEvents.getWaitingEvents(viewer.getEmail(), page).getTotalElements()).isEqualTo(1);
        assertThat(myEvents.getEndedEvents(viewer.getEmail(), page).getTotalElements()).isZero();

        participant.rejoin(now);
        waitlist.delete(entry);
        assertThat(participant.getCanceledAt()).isNull();
        var result = myEvents.getParticipatingEvents(viewer.getEmail(), page);
        assertThat(result.getContent()).singleElement().satisfies(r -> {
            assertThat(r.applicationStatus()).isEqualTo(MyRunningEventApplicationStatus.PARTICIPATING);
            assertThat(r.endReason()).isNull();
            assertThat(r.waitlistPosition()).isNull();
        });
        assertThat(myEvents.getWaitingEvents(viewer.getEmail(), page).getTotalElements()).isZero();
        assertThat(myEvents.getCreatedEvents(creator.getEmail(), page).getTotalElements()).isEqualTo(1);
    }

    @Test
    void servicePreservesEmptyPageMetadataAndRejectsMissingMember() {
        waiting(event(now.plusDays(1)));
        var result = myEvents.getWaitingEvents(viewer.getEmail(), PageRequest.of(3, 1));
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getNumber()).isEqualTo(3);
        assertThatThrownBy(() -> myEvents.getEndedEvents("missing@mypage.test", page))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND));
    }

    @Test
    void detailShowsCreatorNoneWaitingAndPromotionUsingSameRulesAsLists() {
        RunningEvent event = event(now.plusDays(1));
        org.springframework.test.util.ReflectionTestUtils.setField(event, "capacity", 1);
        participants.save(RunningEventParticipant.builder().runningEvent(event).member(creator).build());
        var owner = eventService.getRunningEvent(event.getId(), creator.getEmail());
        assertThat(owner.isCreator()).isTrue();
        assertThat(owner.applicationStatus()).isEqualTo(MyRunningEventApplicationStatus.PARTICIPATING);
        assertThat(owner.participantNicknames()).containsExactly(creator.getNickname());
        var none = eventService.getRunningEvent(event.getId(), viewer.getEmail());
        assertThat(none.isCreator()).isFalse();
        assertThat(none.applicationStatus()).isEqualTo(MyRunningEventApplicationStatus.NONE);

        Member firstWaiting = member();
        participationService.participateRunningEvent(event.getId(), firstWaiting.getEmail());
        participationService.participateRunningEvent(event.getId(), viewer.getEmail());
        var waiting = eventService.getRunningEvent(event.getId(), viewer.getEmail());
        assertThat(waiting.applicationStatus()).isEqualTo(MyRunningEventApplicationStatus.WAITING);
        assertThat(waiting.waitlistPosition()).isEqualTo(2L);
        assertThat(waiting.participantNicknames()).containsExactly(creator.getNickname());
        assertThat(myEvents.getWaitingEvents(viewer.getEmail(), page).getContent())
                .singleElement().satisfies(r -> assertThat(r.waitlistPosition()).isEqualTo(waiting.waitlistPosition()));

        var updated = eventService.updateRunningEvent(event.getId(), new RunningEventUpdateRequestDto(
                event.getTitle(), event.getDescription(), event.getRegion(), event.getRunningPlace(),
                event.getMeetingPlace(), event.getStartAt(), 360, BigDecimal.valueOf(5), 3), creator.getEmail());
        assertThat(updated.isCreator()).isTrue();
        assertThat(updated.applicationStatus()).isEqualTo(MyRunningEventApplicationStatus.PARTICIPATING);
        assertThat(updated.participantNicknames())
                .containsExactly(creator.getNickname(), firstWaiting.getNickname(), viewer.getNickname());
        var promoted = eventService.getRunningEvent(event.getId(), viewer.getEmail());
        assertThat(promoted.applicationStatus()).isEqualTo(MyRunningEventApplicationStatus.PARTICIPATING);
        assertThat(promoted.waitlistPosition()).isNull();
        assertThat(promoted.endReason()).isNull();
        assertThat(promoted.participantNicknames()).isEqualTo(updated.participantNicknames());
        assertThat(myEvents.getWaitingEvents(viewer.getEmail(), page).getTotalElements()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void detailUsesExactWaitlistDeadline(int secondsAfterBoundary) {
        RunningEvent event = event(now.plusMinutes(10).minusSeconds(secondsAfterBoundary));
        waiting(event);
        var response = eventService.getRunningEvent(event.getId(), viewer.getEmail());
        assertThat(response.status()).isEqualTo(com.runmate.runningevent.entity.RunningEventStatus.RECRUITING);
        if (secondsAfterBoundary < 0) {
            assertThat(response.applicationStatus()).isEqualTo(MyRunningEventApplicationStatus.WAITING);
            assertThat(response.waitlistPosition()).isEqualTo(1L);
        } else {
            assertThat(response.applicationStatus()).isEqualTo(MyRunningEventApplicationStatus.ENDED);
            assertThat(response.endReason()).isEqualTo(MyRunningEventEndReason.WAITLIST_CLOSED);
            assertThat(response.waitlistPosition()).isNull();
        }
    }

    @Test
    void detailMatchesLatestCancellationAndEventCancellation() {
        RunningEvent event = event(now.plusDays(1));
        participate(event).cancel(now.minusMinutes(2));
        waiting(event).cancel(now.minusMinutes(1));
        var canceledWait = eventService.getRunningEvent(event.getId(), viewer.getEmail());
        assertThat(canceledWait.endReason()).isEqualTo(MyRunningEventEndReason.WAITLIST_CANCELED);
        assertThat(canceledWait.participantNicknames()).doesNotContain(viewer.getNickname());
        assertThat(myEvents.getEndedEvents(viewer.getEmail(), page).getContent())
                .singleElement().satisfies(r -> assertThat(r.endReason()).isEqualTo(canceledWait.endReason()));
        event.cancel(now);
        assertThat(eventService.getRunningEvent(event.getId(), viewer.getEmail()).endReason())
                .isEqualTo(MyRunningEventEndReason.EVENT_CANCELED);
        assertThat(eventService.getRunningEvent(event.getId(), member().getEmail()).applicationStatus())
                .isEqualTo(MyRunningEventApplicationStatus.NONE);
    }

    @Test
    void detailKeepsPastParticipationWhileClosingRecruitment() {
        RunningEvent event = event(now.minusSeconds(1));
        participate(event);
        var response = eventService.getRunningEvent(event.getId(), viewer.getEmail());
        assertThat(response.status()).isEqualTo(com.runmate.runningevent.entity.RunningEventStatus.CLOSED);
        assertThat(response.applicationStatus()).isEqualTo(MyRunningEventApplicationStatus.PARTICIPATING);
        assertThat(response.waitlistPosition()).isNull();
        assertThat(response.endReason()).isNull();
    }

    private Member member() {
        String name = UUID.randomUUID().toString().substring(0, 8);
        return members.save(Member.builder().email(name + "@mypage.test").password("unused")
                .name(name).nickname(name).region("서울").averagePaceSeconds(360)
                .preferredDistanceKm(BigDecimal.valueOf(5)).build());
    }

    private RunningEvent event(LocalDateTime startAt) {
        return events.save(RunningEvent.builder().title("마이페이지").description("조회 테스트")
                .region("서울").runningPlace("한강").meetingPlace("역").startAt(startAt)
                .preferredPaceSeconds(360).targetDistanceKm(BigDecimal.valueOf(5))
                .capacity(10).creator(creator).build());
    }

    private RunningEventParticipant participate(RunningEvent event) {
        event.increaseCurrentParticipantCount();
        return participants.save(RunningEventParticipant.builder().runningEvent(event).member(viewer).build());
    }

    private RunningEventWaitlistEntry waiting(RunningEvent event) {
        return waitlist.save(RunningEventWaitlistEntry.builder().runningEvent(event).member(viewer)
                .queueNumber(1).requestedAt(now.minusHours(1)).build());
    }
}
