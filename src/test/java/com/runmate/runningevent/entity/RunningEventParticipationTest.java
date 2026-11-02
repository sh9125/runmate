package com.runmate.runningevent.entity;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.member.entity.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunningEventParticipationTest {
    private static final LocalDateTime START = LocalDateTime.of(2030, 11, 1, 20, 0);
    private Member creator;
    private RunningEvent event;
    private RunningEventParticipant participant;

    @BeforeEach
    void setUp() {
        creator = Member.builder().email("creator@example.com").build();
        event = RunningEvent.builder().creator(creator).startAt(START).capacity(2).build();
        participant = RunningEventParticipant.builder().runningEvent(event)
                .member(Member.builder().email("runner@example.com").build()).build();
        event.increaseCurrentParticipantCount();
    }

    @Test
    void fullEventRemainsRecruitingAndRejectsParticipation() {
        assertFailure(() -> event.validateParticipationAllowed(START.minusHours(1)), ErrorCode.EVENT_CAPACITY_FULL);
        assertFailure(event::increaseCurrentParticipantCount, ErrorCode.EVENT_CAPACITY_FULL);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(2);
        assertThat(event.getStatus()).isEqualTo(RunningEventStatus.RECRUITING);
    }

    @Test
    void cancelBeforeDeadlineAndRejoinSameRecord() {
        participant.cancel(START.minusMinutes(10).minusNanos(1));
        assertThat(participant.getStatus()).isEqualTo(RunningEventParticipantStatus.CANCELED);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(RunningEventStatus.RECRUITING);

        // 취소 마감 이후에도 시작 전까지는 재참가할 수 있다.
        participant.rejoin(START.minusNanos(1));
        assertThat(participant.getStatus()).isEqualTo(RunningEventParticipantStatus.PARTICIPATING);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(2);
        assertFailure(() -> participant.cancel(START.minusNanos(1)),
                ErrorCode.PARTICIPATION_CANCELLATION_DEADLINE_PASSED);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10, 11})
    void cancellationRejectedAtOrAfterDeadline(int minutesAfterDeadline) {
        assertFailure(() -> participant.cancel(START.minusMinutes(10).plusMinutes(minutesAfterDeadline)),
                ErrorCode.PARTICIPATION_CANCELLATION_DEADLINE_PASSED);
        assertUnchangedParticipation();
    }

    @Test
    void repeatedCancellationDoesNotDecreaseCountTwice() {
        participant.cancel(START.minusHours(1));
        assertFailure(() -> participant.cancel(START.minusHours(1)), ErrorCode.NOT_PARTICIPATING);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
        assertThat(participant.getStatus()).isEqualTo(RunningEventParticipantStatus.CANCELED);
    }

    @Test
    void creatorCannotCancelOwnParticipation() {
        RunningEventParticipant creatorParticipant = RunningEventParticipant.builder()
                .runningEvent(event).member(creator).build();
        assertFailure(() -> creatorParticipant.cancel(START.minusHours(1)),
                ErrorCode.CREATOR_CANNOT_CANCEL_PARTICIPATION);
        assertThat(creatorParticipant.getStatus()).isEqualTo(RunningEventParticipantStatus.PARTICIPATING);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(2);
    }

    @Test
    void duplicateRejoinDoesNotIncreaseCount() {
        assertFailure(() -> participant.rejoin(START.minusHours(1)), ErrorCode.ALREADY_PARTICIPATING);
        assertUnchangedParticipation();
    }

    @Test
    void rejoinRejectedIfSomeoneElseTookVacancy() {
        participant.cancel(START.minusHours(1));
        event.increaseCurrentParticipantCount();
        assertFailure(() -> participant.rejoin(START.minusMinutes(5)), ErrorCode.EVENT_CAPACITY_FULL);
        assertThat(participant.getStatus()).isEqualTo(RunningEventParticipantStatus.CANCELED);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void startTimeBlocksRejoinEvenBeforeSchedulerRuns(int secondsAfterStart) {
        participant.cancel(START.minusHours(1));
        assertFailure(() -> participant.rejoin(START.plusSeconds(secondsAfterStart)), ErrorCode.EVENT_ALREADY_STARTED);
        assertThat(event.getStatus()).isEqualTo(RunningEventStatus.RECRUITING);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
        assertThat(participant.getStatus()).isEqualTo(RunningEventParticipantStatus.CANCELED);
    }

    @ParameterizedTest
    @EnumSource(value = RunningEventStatus.class, names = {"CLOSED", "CANCELED", "COMPLETED"})
    void nonRecruitingEventRejectsRejoin(RunningEventStatus status) {
        participant.cancel(START.minusHours(1));
        ReflectionTestUtils.setField(event, "status", status);
        assertFailure(() -> participant.rejoin(START.minusMinutes(20)),
                status == RunningEventStatus.CANCELED ? ErrorCode.EVENT_CANCELED : ErrorCode.EVENT_NOT_RECRUITING);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
        assertThat(participant.getStatus()).isEqualTo(RunningEventParticipantStatus.CANCELED);
    }

    @ParameterizedTest
    @EnumSource(value = RunningEventStatus.class, names = {"CANCELED", "COMPLETED"})
    void canceledOrCompletedEventRejectsPersonalCancellation(RunningEventStatus status) {
        ReflectionTestUtils.setField(event, "status", status);
        assertFailure(() -> participant.cancel(START.minusHours(1)), ErrorCode.PARTICIPATION_NOT_CANCELABLE);
        assertUnchangedParticipation();
    }

    @Test
    void closedEventStillAllowsCancellationBeforeDeadline() {
        ReflectionTestUtils.setField(event, "status", RunningEventStatus.CLOSED);
        participant.cancel(START.minusHours(1));
        assertThat(event.getStatus()).isEqualTo(RunningEventStatus.CLOSED);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
    }

    @Test
    void eventCancellationPreservesPersonalParticipationStatesAndCount() {
        participant.cancel(START.minusHours(1));
        RunningEventParticipant creatorParticipant = RunningEventParticipant.builder()
                .runningEvent(event).member(creator).build();
        event.cancel(START.minusHours(1));
        assertThat(event.getStatus()).isEqualTo(RunningEventStatus.CANCELED);
        assertThat(participant.getStatus()).isEqualTo(RunningEventParticipantStatus.CANCELED);
        assertThat(creatorParticipant.getStatus()).isEqualTo(RunningEventParticipantStatus.PARTICIPATING);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
    }

    @Test
    void recruitmentClosesExactlyAtStartAndRepeatedCallIsHarmless() {
        event.closeRecruitmentIfStarted(START.minusNanos(1));
        assertThat(event.getStatus()).isEqualTo(RunningEventStatus.RECRUITING);
        event.closeRecruitmentIfStarted(START);
        assertThat(event.getStatus()).isEqualTo(RunningEventStatus.CLOSED);
        event.closeRecruitmentIfStarted(START.plusMinutes(1));
        assertThat(event.getStatus()).isEqualTo(RunningEventStatus.CLOSED);
        assertUnchangedParticipation();
    }

    @ParameterizedTest
    @EnumSource(value = RunningEventStatus.class, names = {"CANCELED", "COMPLETED"})
    void recruitmentClosingDoesNotOverwriteTerminalStatus(RunningEventStatus status) {
        ReflectionTestUtils.setField(event, "status", status);
        event.closeRecruitmentIfStarted(START);
        assertThat(event.getStatus()).isEqualTo(status);
    }

    private void assertUnchangedParticipation() {
        assertThat(participant.getStatus()).isEqualTo(RunningEventParticipantStatus.PARTICIPATING);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(2);
    }

    private void assertFailure(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode()).isEqualTo(expected);
    }
}
