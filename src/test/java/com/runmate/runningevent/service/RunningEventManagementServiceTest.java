package com.runmate.runningevent.service;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import com.runmate.runningevent.dto.request.RunningEventUpdateRequestDto;
import com.runmate.runningevent.dto.response.RunningEventResponseDto;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventStatus;
import com.runmate.runningevent.repository.RunningEventParticipantRepository;
import com.runmate.runningevent.repository.RunningEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunningEventManagementServiceTest {
    private static final String CREATOR_EMAIL = "creator@example.com";
    private static final LocalDateTime START = LocalDateTime.of(2030, 11, 1, 19, 0);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Mock
    private RunningEventRepository eventRepository;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private RunningEventParticipantRepository participantRepository;

    private RunningEvent event;

    enum Operation { UPDATE, CANCEL }

    @BeforeEach
    void setUp() {
        Member creator = Member.builder().email(CREATOR_EMAIL).nickname("생성자").build();
        ReflectionTestUtils.setField(creator, "id", 1L);
        event = RunningEvent.builder()
                .title("원래 제목").description("원래 설명").region("서울")
                .runningPlace("한강공원").meetingPlace("여의나루역")
                .startAt(START).preferredPaceSeconds(360).targetDistanceKm(BigDecimal.valueOf(5))
                .capacity(10).creator(creator).build();
        ReflectionTestUtils.setField(event, "id", 10L);
    }

    @Test
    void creatorCanUpdateAllEditableFieldsBeforeDeadline() {
        givenEvent();
        RunningEventUpdateRequestDto request = request("부산", "시민공원", "정문", START.plusDays(1), 20);

        RunningEventResponseDto response = serviceAt(START.minusHours(1))
                .updateRunningEvent(10L, request, CREATOR_EMAIL);

        assertThat(response.title()).isEqualTo("수정 제목");
        assertThat(response.description()).isEqualTo("수정 설명");
        assertThat(response.region()).isEqualTo("부산");
        assertThat(response.runningPlace()).isEqualTo("시민공원");
        assertThat(response.meetingPlace()).isEqualTo("정문");
        assertThat(response.startAt()).isEqualTo(START.plusDays(1));
        assertThat(response.preferredPaceSeconds()).isEqualTo(400);
        assertThat(response.targetDistanceKm()).isEqualByComparingTo("7.5");
        assertThat(response.capacity()).isEqualTo(20);
        assertThat(response.currentParticipantCount()).isEqualTo(1);
        assertThat(response.creatorId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(RunningEventStatus.RECRUITING);
        verify(eventRepository).flush();
        verify(eventRepository, never()).save(any());
        verifyNoInteractions(participantRepository);
    }

    @Test
    void cancelPreservesParticipantCountAndRecords() {
        givenEvent();
        ReflectionTestUtils.setField(event, "currentParticipantCount", 3);

        RunningEventResponseDto response = serviceAt(START.minusHours(1))
                .cancelRunningEvent(10L, CREATOR_EMAIL);

        assertThat(response.status()).isEqualTo(RunningEventStatus.CANCELED);
        assertThat(response.currentParticipantCount()).isEqualTo(3);
        verify(eventRepository).flush();
        verifyNoInteractions(participantRepository);
        verify(eventRepository, never()).delete(any());
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void lastNanosecondBeforeDeadlineIsAllowed(Operation operation) {
        givenEvent();
        perform(operation, serviceAt(START.minusMinutes(15).minusNanos(1)), CREATOR_EMAIL);
        assertThat(event.getTitle()).isEqualTo(operation == Operation.UPDATE ? "수정 제목" : "원래 제목");
        assertThat(event.getStatus()).isEqualTo(operation == Operation.CANCEL
                ? RunningEventStatus.CANCELED : RunningEventStatus.RECRUITING);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void exactDeadlineIsRejected(Operation operation) {
        givenEvent();
        assertFailure(() -> perform(operation, serviceAt(START.minusMinutes(15)), CREATOR_EMAIL),
                ErrorCode.EVENT_MANAGEMENT_DEADLINE_PASSED);
        assertUnchanged();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void firstNanosecondAfterDeadlineIsRejected(Operation operation) {
        givenEvent();
        assertFailure(() -> perform(operation, serviceAt(START.minusMinutes(15).plusNanos(1)), CREATOR_EMAIL),
                ErrorCode.EVENT_MANAGEMENT_DEADLINE_PASSED);
        assertUnchanged();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void afterStartIsRejected(Operation operation) {
        givenEvent();
        assertFailure(() -> perform(operation, serviceAt(START.plusMinutes(1)), CREATOR_EMAIL),
                ErrorCode.EVENT_MANAGEMENT_DEADLINE_PASSED);
        assertUnchanged();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void anotherMemberCannotManageEvent(Operation operation) {
        givenEvent();
        assertFailure(() -> perform(operation, serviceAt(START.minusHours(1)), "other@example.com"),
                ErrorCode.NOT_EVENT_CREATOR);
        assertUnchanged();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void missingEventIsRejected(Operation operation) {
        when(eventRepository.findById(10L)).thenReturn(Optional.empty());
        assertFailure(() -> perform(operation, serviceAt(START.minusHours(1)), CREATOR_EMAIL),
                ErrorCode.RUNNING_EVENT_NOT_FOUND);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void canceledEventCannotBeManagedAgain(Operation operation) {
        givenEvent();
        ReflectionTestUtils.setField(event, "status", RunningEventStatus.CANCELED);
        assertFailure(() -> perform(operation, serviceAt(START.minusHours(1)), CREATOR_EMAIL),
                ErrorCode.EVENT_NOT_MANAGEABLE);
        assertThat(event.getStatus()).isEqualTo(RunningEventStatus.CANCELED);
        assertThat(event.getTitle()).isEqualTo("원래 제목");
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void completedEventCannotBeManaged(Operation operation) {
        givenEvent();
        ReflectionTestUtils.setField(event, "status", RunningEventStatus.COMPLETED);
        assertFailure(() -> perform(operation, serviceAt(START.minusHours(1)), CREATOR_EMAIL),
                ErrorCode.EVENT_NOT_MANAGEABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"startAt", "region", "runningPlace", "meetingPlace"})
    void participantsPreventDateAndLocationChanges(String field) {
        givenEvent();
        ReflectionTestUtils.setField(event, "currentParticipantCount", 2);
        RunningEventUpdateRequestDto request = sameDetailsRequest(10);
        ReflectionTestUtils.setField(request, field,
                field.equals("startAt") ? START.plusDays(1) : "다른 장소");

        assertFailure(() -> serviceAt(START.minusHours(1))
                .updateRunningEvent(10L, request, CREATOR_EMAIL), ErrorCode.EVENT_DETAILS_LOCKED);
        assertUnchanged();
    }

    @Test
    void capacityCannotBeLessThanCurrentCount() {
        givenEvent();
        ReflectionTestUtils.setField(event, "currentParticipantCount", 3);
        assertFailure(() -> serviceAt(START.minusHours(1))
                .updateRunningEvent(10L, sameDetailsRequest(2), CREATOR_EMAIL),
                ErrorCode.CAPACITY_BELOW_PARTICIPANTS);
        assertUnchanged();
    }

    @Test
    void participantsStillAllowOtherFieldsAndCapacityEqualToCurrentCount() {
        givenEvent();
        ReflectionTestUtils.setField(event, "currentParticipantCount", 3);
        RunningEventResponseDto response = serviceAt(START.minusHours(1))
                .updateRunningEvent(10L, sameDetailsRequest(3), CREATOR_EMAIL);

        assertThat(response.title()).isEqualTo("수정 제목");
        assertThat(response.capacity()).isEqualTo(3);
        assertThat(response.currentParticipantCount()).isEqualTo(3);
        assertThat(response.startAt()).isEqualTo(START);
        assertThat(response.runningPlace()).isEqualTo("한강공원");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void newStartTimeMustBeMoreThanFifteenMinutesAway(long nanosecondsAfterMinimum) {
        givenEvent();
        LocalDateTime now = START.minusHours(1);
        RunningEventUpdateRequestDto request = request("서울", "한강공원", "여의나루역",
                now.plusMinutes(15).plusNanos(nanosecondsAfterMinimum), 10);
        assertFailure(() -> serviceAt(now).updateRunningEvent(10L, request, CREATOR_EMAIL),
                ErrorCode.EVENT_START_TOO_SOON);
        assertUnchanged();
    }

    @Test
    void newStartTimeOneNanosecondBeyondMinimumIsAllowed() {
        givenEvent();
        LocalDateTime now = START.minusHours(1);
        LocalDateTime newStart = now.plusMinutes(15).plusNanos(1);
        RunningEventUpdateRequestDto request = request("서울", "한강공원", "여의나루역", newStart, 10);
        assertThat(serviceAt(now).updateRunningEvent(10L, request, CREATOR_EMAIL).startAt())
                .isEqualTo(newStart);
    }

    @Test
    void movingStartIntoFutureCannotBypassOriginalDeadline() {
        givenEvent();
        RunningEventUpdateRequestDto request = request("서울", "한강공원", "여의나루역", START.plusDays(1), 10);
        assertFailure(() -> serviceAt(START.minusMinutes(15))
                .updateRunningEvent(10L, request, CREATOR_EMAIL),
                ErrorCode.EVENT_MANAGEMENT_DEADLINE_PASSED);
        assertUnchanged();
    }

    private void givenEvent() {
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
    }

    private RunningEventService serviceAt(LocalDateTime now) {
        Clock clock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE);
        return new RunningEventService(eventRepository, memberRepository, participantRepository, clock);
    }

    private void perform(Operation operation, RunningEventService service, String email) {
        if (operation == Operation.UPDATE) {
            service.updateRunningEvent(10L, sameDetailsRequest(10), email);
        } else {
            service.cancelRunningEvent(10L, email);
        }
    }

    private RunningEventUpdateRequestDto sameDetailsRequest(int capacity) {
        return request("서울", "한강공원", "여의나루역", START, capacity);
    }

    private RunningEventUpdateRequestDto request(String region, String runningPlace, String meetingPlace,
                                                LocalDateTime startAt, int capacity) {
        return new RunningEventUpdateRequestDto("수정 제목", "수정 설명", region, runningPlace,
                meetingPlace, startAt, 400, new BigDecimal("7.5"), capacity);
    }

    private void assertFailure(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(errorCode);
        verify(eventRepository, never()).flush();
    }

    private void assertUnchanged() {
        assertThat(event.getTitle()).isEqualTo("원래 제목");
        assertThat(event.getDescription()).isEqualTo("원래 설명");
        assertThat(event.getStartAt()).isEqualTo(START);
        assertThat(event.getRegion()).isEqualTo("서울");
        assertThat(event.getRunningPlace()).isEqualTo("한강공원");
        assertThat(event.getMeetingPlace()).isEqualTo("여의나루역");
        assertThat(event.getCapacity()).isEqualTo(10);
        assertThat(event.getStatus()).isEqualTo(RunningEventStatus.RECRUITING);
    }
}
