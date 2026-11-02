package com.runmate.runningevent.service;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;

import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import com.runmate.runningevent.dto.response.RunningEventParticipantResponseDto;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventParticipant;
import com.runmate.runningevent.entity.RunningEventParticipantStatus;
import com.runmate.runningevent.entity.RunningEventStatus;
import com.runmate.runningevent.repository.RunningEventParticipantRepository;
import com.runmate.runningevent.repository.RunningEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RunningEventParticipantServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 11, 1, 18, 0);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    @Mock
    private MemberRepository memberRepository;

    @Mock
    private RunningEventRepository runningEventRepository;

    @Mock
    private RunningEventParticipantRepository runningEventParticipantRepository;

    private  RunningEventParticipantService runningEventParticipantService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        runningEventParticipantService = new RunningEventParticipantService(
                runningEventParticipantRepository, memberRepository, runningEventRepository, clock);
    }

    @Test
    @DisplayName("러닝 이벤트 참가를 성공한다.")
    void createRunningEventParticipant() {
        //given
        Member creator = createMember("홍길동", "example@test.com");
        Member member = createMember("바보", "babao@test.com");
        RunningEvent runningEvent = createRunningEvent(creator);

        ReflectionTestUtils.setField(creator, "id", 2L);
        ReflectionTestUtils.setField(member, "id", 3L);
        ReflectionTestUtils.setField(runningEvent, "id", 10L);

        when(memberRepository.findByEmail(member.getEmail()))
                .thenReturn(Optional.of(member));
        when(runningEventRepository.findById(runningEvent.getId()))
                .thenReturn(Optional.of(runningEvent));
        when(runningEventParticipantRepository.findByRunningEvent_IdAndMember_Id(runningEvent.getId(), member.getId()))
                .thenReturn(Optional.empty());
        when(runningEventParticipantRepository.save(any(RunningEventParticipant.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0, RunningEventParticipant.class));

        //when
        RunningEventParticipantResponseDto response = runningEventParticipantService.participateRunningEvent(runningEvent.getId(), member.getEmail());

        //then
        assertThat(response.eventId()).isEqualTo(runningEvent.getId());
        assertThat(response.memberId()).isEqualTo(member.getId());
        assertThat(response.status()).isEqualTo(RunningEventParticipantStatus.PARTICIPATING);
        assertThat(runningEvent.getCurrentParticipantCount()).isEqualTo(2);

        verify(runningEventParticipantRepository)
                .save(any(RunningEventParticipant.class));

    }

    @Test
    @DisplayName("중복 참가 실패")
    void failOfDuplication() {
        //given
        Member creator = createMember("홍길동", "example@test.com");
        Member member = createMember("바부", "babo@test.com");
        RunningEvent runningEvent = createRunningEvent(creator);
        ReflectionTestUtils.setField(creator, "id", 1L);
        ReflectionTestUtils.setField(member,"id", 2L);
        ReflectionTestUtils.setField(runningEvent,"id", 10L);

        when(memberRepository.findByEmail(member.getEmail()))
                .thenReturn(Optional.of(member));
        when(runningEventRepository.findById(runningEvent.getId()))
                .thenReturn(Optional.of(runningEvent));
        RunningEventParticipant existingParticipant = RunningEventParticipant.builder()
                .runningEvent(runningEvent).member(member).build();
        when(runningEventParticipantRepository.findByRunningEvent_IdAndMember_Id(runningEvent.getId(), member.getId()))
                .thenReturn(Optional.of(existingParticipant));

        //when & then
        assertThatThrownBy(() -> runningEventParticipantService.participateRunningEvent(runningEvent.getId(), member.getEmail()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 참가한 러닝 이벤트 입니다.")
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_PARTICIPATING);

        assertThat(runningEvent.getCurrentParticipantCount()).isEqualTo(1);
        verify(runningEventParticipantRepository, never())
                .save(any(RunningEventParticipant.class));

    }

    @Test
    @DisplayName("없는 회원이 러닝 이벤트 참여하면 실패")
    void failOfNoneMember() {
        //given
        Member creator = createMember("홍길동", "example@test.com");
        Member member = createMember("바부", "babo@test.com");
        RunningEvent runningEvent = createRunningEvent(creator);
        ReflectionTestUtils.setField(creator, "id", 1L);
        ReflectionTestUtils.setField(runningEvent,"id", 10L);

        when(memberRepository.findByEmail(member.getEmail()))
                .thenReturn(Optional.empty());

        //when & then
        assertThatThrownBy(() -> runningEventParticipantService.participateRunningEvent(runningEvent.getId(), member.getEmail()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("존재하지 않는 회원입니다.")
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        verify(runningEventParticipantRepository, never())
                .save(any(RunningEventParticipant.class));

    }

    @Test
    @DisplayName("없는 러닝 이벤트 참여하면 실패")
    void failOfNoneRunningEvent() {
        //given
        Long eventId = 10L;
        Member member = createMember("바부", "babo@test.com");

        when(memberRepository.findByEmail(member.getEmail()))
                .thenReturn(Optional.of(member));
        when(runningEventRepository.findById(eventId))
                .thenReturn(Optional.empty());

        //when & then
        assertThatThrownBy(() -> runningEventParticipantService.participateRunningEvent(eventId, member.getEmail()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("존재하지 않는 러닝 이벤트입니다.")
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.RUNNING_EVENT_NOT_FOUND);
        verify(runningEventParticipantRepository, never())
                .save(any(RunningEventParticipant.class));

    }

    @Test
    @DisplayName("취소된 이벤트에는 참가 기록을 저장하거나 인원을 증가시키지 않는다")
    void canceledEventRejectsParticipation() {
        Member member = createMember("참가자", "runner@example.com");
        RunningEvent event = createRunningEvent(createMember("생성자", "creator@example.com"));
        ReflectionTestUtils.setField(event, "status", RunningEventStatus.CANCELED);
        when(memberRepository.findByEmail(member.getEmail())).thenReturn(Optional.of(member));
        when(runningEventRepository.findById(10L)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> runningEventParticipantService.participateRunningEvent(10L, member.getEmail()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.EVENT_CANCELED);

        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
        verifyNoInteractions(runningEventParticipantRepository);
    }

    @Test
    @DisplayName("취소했던 참가자는 마지막 한 자리에 같은 기록으로 재참가하고 인원은 한 번만 증가한다")
    void rejoinUsesExistingRecordAndIncreasesCountOnce() {
        Member member = createMember("참가자", "runner@example.com");
        RunningEvent event = createRunningEvent(createMember("생성자", "creator@example.com"));
        ReflectionTestUtils.setField(member, "id", 2L);
        ReflectionTestUtils.setField(event, "id", 10L);
        ReflectionTestUtils.setField(event, "capacity", 2);
        RunningEventParticipant existingParticipant = RunningEventParticipant.builder()
                .runningEvent(event).member(member).build();
        ReflectionTestUtils.setField(existingParticipant, "id", 100L);
        event.increaseCurrentParticipantCount();
        existingParticipant.cancel(NOW.minusMinutes(1));
        // 취소 마감은 지났어도 시작 전이면 재참가 가능하다.
        ReflectionTestUtils.setField(event, "startAt", NOW.plusMinutes(5));
        when(memberRepository.findByEmail(member.getEmail())).thenReturn(Optional.of(member));
        when(runningEventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(runningEventParticipantRepository.findByRunningEvent_IdAndMember_Id(10L, member.getId()))
                .thenReturn(Optional.of(existingParticipant));

        RunningEventParticipantResponseDto response =
                runningEventParticipantService.participateRunningEvent(10L, member.getEmail());

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo(RunningEventParticipantStatus.PARTICIPATING);
        assertThat(existingParticipant.getStatus()).isEqualTo(RunningEventParticipantStatus.PARTICIPATING);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(2);
        verify(runningEventParticipantRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = RunningEventStatus.class, names = {"CLOSED", "COMPLETED"})
    @DisplayName("최초 신청도 모집 중이 아니면 저장하거나 인원을 증가시키지 않는다")
    void firstParticipationRejectsNonRecruitingEvent(RunningEventStatus status) {
        Member member = createMember("참가자", "runner@example.com");
        RunningEvent event = createRunningEvent(createMember("생성자", "creator@example.com"));
        ReflectionTestUtils.setField(event, "status", status);
        givenNoParticipation(member, event);

        assertThatThrownBy(() -> runningEventParticipantService.participateRunningEvent(10L, member.getEmail()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.EVENT_NOT_RECRUITING);

        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
        verify(runningEventParticipantRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @DisplayName("DB 상태가 모집 중이어도 시작 시각부터 최초 신청을 거절한다")
    void firstParticipationRejectsAtOrAfterStart(int secondsAfterStart) {
        Member member = createMember("참가자", "runner@example.com");
        RunningEvent event = createRunningEvent(createMember("생성자", "creator@example.com"));
        ReflectionTestUtils.setField(event, "startAt", NOW.minusSeconds(secondsAfterStart));
        givenNoParticipation(member, event);

        assertThatThrownBy(() -> runningEventParticipantService.participateRunningEvent(10L, member.getEmail()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.EVENT_ALREADY_STARTED);

        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
        verify(runningEventParticipantRepository, never()).save(any());
    }

    @Test
    @DisplayName("정원이 가득 차면 최초 신청 기록을 저장하기 전에 거절한다")
    void firstParticipationRejectsFullEventBeforeSaving() {
        Member member = createMember("참가자", "runner@example.com");
        RunningEvent event = createRunningEvent(createMember("생성자", "creator@example.com"));
        ReflectionTestUtils.setField(event, "capacity", 1);
        givenNoParticipation(member, event);

        assertThatThrownBy(() -> runningEventParticipantService.participateRunningEvent(10L, member.getEmail()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.EVENT_CAPACITY_FULL);

        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
        verify(runningEventParticipantRepository, never()).save(any());
    }

    @Test
    @DisplayName("개인 취소는 기존 기록의 상태와 인원만 변경하고 반복 취소는 거절한다")
    void cancelParticipationAndRejectRepeat() {
        Member member = createMember("참가자", "runner@example.com");
        RunningEvent event = createRunningEvent(createMember("생성자", "creator@example.com"));
        ReflectionTestUtils.setField(member, "id", 2L);
        ReflectionTestUtils.setField(event, "id", 10L);
        event.increaseCurrentParticipantCount();
        RunningEventParticipant participant = RunningEventParticipant.builder()
                .runningEvent(event).member(member).build();
        ReflectionTestUtils.setField(participant, "id", 100L);
        when(memberRepository.findByEmail(member.getEmail())).thenReturn(Optional.of(member));
        when(runningEventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(runningEventParticipantRepository.findByRunningEvent_IdAndMember_Id(10L, member.getId()))
                .thenReturn(Optional.of(participant));

        RunningEventParticipantResponseDto response =
                runningEventParticipantService.cancelParticipation(10L, member.getEmail());

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo(RunningEventParticipantStatus.CANCELED);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(RunningEventStatus.RECRUITING);
        assertThatThrownBy(() -> runningEventParticipantService.cancelParticipation(10L, member.getEmail()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.NOT_PARTICIPATING);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
        verify(runningEventParticipantRepository, never()).save(any());
        verify(runningEventParticipantRepository, never()).delete(any());
    }

    @Test
    @DisplayName("개인 취소 요청 회원의 참가 기록이 없으면 다른 참가 기록이나 인원을 변경하지 않는다")
    void cancelParticipationWithoutRecord() {
        Member member = createMember("미참가자", "runner@example.com");
        RunningEvent event = createRunningEvent(createMember("생성자", "creator@example.com"));
        givenNoParticipation(member, event);

        assertThatThrownBy(() -> runningEventParticipantService.cancelParticipation(10L, member.getEmail()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.NOT_PARTICIPATING);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
        verify(runningEventParticipantRepository, never()).save(any());
    }

    @Test
    @DisplayName("개인 취소 대상 이벤트가 없으면 미참가 오류 대신 이벤트 없음 오류를 반환한다")
    void cancelParticipationWithMissingEvent() {
        Member member = createMember("참가자", "runner@example.com");
        when(memberRepository.findByEmail(member.getEmail())).thenReturn(Optional.of(member));
        when(runningEventRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> runningEventParticipantService.cancelParticipation(10L, member.getEmail()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.RUNNING_EVENT_NOT_FOUND);
        verifyNoInteractions(runningEventParticipantRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @DisplayName("서비스가 고정 Clock의 현재 시각으로 정확한 개인 취소 마감을 적용한다")
    void cancelParticipationAtOrAfterDeadline(int secondsAfterDeadline) {
        Member member = createMember("참가자", "runner@example.com");
        RunningEvent event = createRunningEvent(createMember("생성자", "creator@example.com"));
        ReflectionTestUtils.setField(member, "id", 2L);
        ReflectionTestUtils.setField(event, "startAt", NOW.plusMinutes(10).minusSeconds(secondsAfterDeadline));
        event.increaseCurrentParticipantCount();
        RunningEventParticipant participant = RunningEventParticipant.builder()
                .runningEvent(event).member(member).build();
        when(memberRepository.findByEmail(member.getEmail())).thenReturn(Optional.of(member));
        when(runningEventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(runningEventParticipantRepository.findByRunningEvent_IdAndMember_Id(10L, member.getId()))
                .thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> runningEventParticipantService.cancelParticipation(10L, member.getEmail()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.PARTICIPATION_CANCELLATION_DEADLINE_PASSED);
        assertThat(participant.getStatus()).isEqualTo(RunningEventParticipantStatus.PARTICIPATING);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(2);
    }

    private void givenNoParticipation(Member member, RunningEvent event) {
        ReflectionTestUtils.setField(member, "id", 2L);
        when(memberRepository.findByEmail(member.getEmail())).thenReturn(Optional.of(member));
        when(runningEventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(runningEventParticipantRepository.findByRunningEvent_IdAndMember_Id(10L, member.getId()))
                .thenReturn(Optional.empty());
    }

    private Member createMember(String name, String email) {
        return Member.builder()
                .email(email)
                .password("encoded-password")
                .name(name)
                .nickname("밤빛폭격기")
                .region("서울")
                .averagePaceSeconds(360)
                .preferredDistanceKm(BigDecimal.valueOf(5))
                .build();
    }

    private RunningEvent createRunningEvent(Member creator) {
        return RunningEvent.builder()
                .title("한강 야간 러닝")
                .description("함께 가볍게 달려요.")
                .region("서울")
                .runningPlace("한강공원")
                .meetingPlace("여의나루역 2번 출구")
                .startAt(NOW.plusHours(1))
                .preferredPaceSeconds(360)
                .targetDistanceKm(BigDecimal.valueOf(5))
                .capacity(10)
                .creator(creator)
                .build();
    }
}
