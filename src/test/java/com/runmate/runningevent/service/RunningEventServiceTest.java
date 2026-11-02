package com.runmate.runningevent.service;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;

import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import com.runmate.runningevent.dto.request.RunningEventCreateRequestDto;
import com.runmate.runningevent.dto.response.RunningEventListResponseDto;
import com.runmate.runningevent.dto.response.RunningEventResponseDto;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventParticipant;
import com.runmate.runningevent.entity.RunningEventStatus;
import com.runmate.runningevent.mapper.RunningEventMapper;
import com.runmate.runningevent.repository.RunningEventParticipantRepository;
import com.runmate.runningevent.repository.RunningEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RunningEventServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 11, 1, 20, 0);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    @Mock
    private RunningEventRepository  runningEventRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private RunningEventParticipantRepository runningEventParticipantRepository;

    private RunningEventService runningEventService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        runningEventService = new RunningEventService(runningEventRepository, memberRepository,
                runningEventParticipantRepository, clock);
    }

    @Test
    @DisplayName("로그인한 회원이 러닝 이벤트를 생성한다.")
    void createRunningEventSuccess() {
        //given
        Member creator = createMember();
        RunningEventCreateRequestDto request = createRequest();

        when(memberRepository.findByEmail(creator.getEmail()))
                .thenReturn(Optional.of(creator));

        when(runningEventRepository.save(any(RunningEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, RunningEvent.class));

        //when
        RunningEventResponseDto response = runningEventService.createRunningEvent(request, creator.getEmail());

        //then
        assertThat(response.title()).isEqualTo(request.getTitle());
        assertThat(response.capacity()).isEqualTo(request.getCapacity());
        assertThat(response.currentParticipantCount()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(RunningEventStatus.RECRUITING);
        assertThat(response.creatorNickname()).isEqualTo(creator.getNickname());
        verify(memberRepository).findByEmail(creator.getEmail());
        ArgumentCaptor<RunningEvent> eventCaptor = ArgumentCaptor.forClass(RunningEvent.class);

        verify(runningEventRepository).save(eventCaptor.capture());

        ArgumentCaptor<RunningEventParticipant> captor = ArgumentCaptor.forClass(RunningEventParticipant.class);

        verify(runningEventParticipantRepository).save(captor.capture());

        RunningEventParticipant participant = captor.getValue();

        assertThat(participant.getMember()).isSameAs(creator);

        assertThat(participant.getRunningEvent()).isSameAs(eventCaptor.getValue());
    }

    @Test
    @DisplayName("존재하지 않는 회원이면 러닝 이벤트 생성에 실패한다")
    void createRunningEventFailByMemberNotFound() {
        //given
        RunningEventCreateRequestDto request = createRequest();
        String email = "unknown@example.com";

        when(memberRepository.findByEmail(email))
                .thenReturn(Optional.empty());

        //when

        //then
        assertThatThrownBy(() -> runningEventService.createRunningEvent(request, email))
                .isInstanceOf(BusinessException.class)
                .hasMessage("존재하지 않는 회원입니다.")
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);

        verify(runningEventRepository, never()).save(any(RunningEvent.class));
    }

    @Test
    @DisplayName("목록 조회에 성공한다")
    void getRunningEventsListSuccess() {
        //given
        PageRequest pageRequest = PageRequest.of(0, 10);
        Member creator = createMember();
        RunningEventCreateRequestDto request = createRequest();
        RunningEvent runningEvent = RunningEventMapper.toEntity(request, creator);

        when(runningEventRepository.findAll(pageRequest))
                .thenReturn(new PageImpl<>(List.of(runningEvent), pageRequest, 1));

        //when
        Page<RunningEventListResponseDto> response = runningEventService.getRunningEvents(pageRequest);

        //then
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).title()).isEqualTo("한강 야간 러닝");
        assertThat(response.getContent().get(0).creatorNickname()).isEqualTo("밤빛폭격기");
        verify(runningEventRepository).findAll(pageRequest);
    }

    @Test
    @DisplayName("상세 정보 조회에 성공한다")
    void getRunningEventSuccess() {
        //given
        Long eventId = 1L;
        Member creator = createMember();
        RunningEventCreateRequestDto request = createRequest();
        RunningEvent runningEvent = RunningEventMapper.toEntity(request, creator);
        when(runningEventRepository.findById(eventId))
                .thenReturn((Optional.of(runningEvent)));

        //when
        RunningEventResponseDto response = runningEventService.getRunningEvent(eventId);

        //then
        assertThat(response.title()).isEqualTo("한강 야간 러닝");
        assertThat(response.description()).isEqualTo("함께 가볍게 달려요.");
        assertThat(response.creatorNickname()).isEqualTo("밤빛폭격기");
        verify(runningEventRepository).findById(eventId);
    }

    @Test
    @DisplayName("존재하지 않는 이벤트 상세 조회 실패")
    void getRunningEventFail() {
        //given
        Long eventId = 999L;
        when(runningEventRepository.findById(eventId))
                .thenReturn((Optional.empty()));
        //when

        //then
        assertThatThrownBy(() -> runningEventService.getRunningEvent(eventId))
                .isInstanceOf(BusinessException.class)
                .hasMessage("존재하지 않는 러닝 이벤트입니다.")
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.RUNNING_EVENT_NOT_FOUND);

        verify(runningEventRepository).findById(eventId);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    @DisplayName("상세 조회는 시작 직전에는 모집을 유지하고 정확한 시작 시각부터 엔티티와 응답을 마감한다")
    void detailRefreshesStatusAtStartBoundary(int startOffsetSeconds) {
        RunningEvent event = eventAt(1L, NOW.plusSeconds(startOffsetSeconds));
        when(runningEventRepository.findById(1L)).thenReturn(Optional.of(event));

        RunningEventResponseDto response = runningEventService.getRunningEvent(1L);

        RunningEventStatus expected = startOffsetSeconds <= 0
                ? RunningEventStatus.CLOSED : RunningEventStatus.RECRUITING;
        assertThat(event.getStatus()).isEqualTo(expected);
        assertThat(response.status()).isEqualTo(expected);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
        assertThat(response.currentParticipantCount()).isEqualTo(1);
        verifyNoInteractions(memberRepository, runningEventParticipantRepository);
        verify(runningEventRepository).findById(1L);
        if (startOffsetSeconds <= 0) {
            verify(runningEventRepository).flush();
        }
        verifyNoMoreInteractions(runningEventRepository);
    }

    @Test
    @DisplayName("상세 조회에서 마감 상태를 변경하면 flush 이후 갱신된 수정 시각으로 응답한다")
    void detailReturnsUpdatedAtAfterRecruitmentClosureFlush() {
        RunningEvent event = eventAt(1L, NOW);
        ReflectionTestUtils.setField(event, "updatedAt", NOW.minusDays(1));
        when(runningEventRepository.findById(1L)).thenReturn(Optional.of(event));
        // Auditing 자체 대신 flush 시점의 값 변경을 모사해 DTO 생성 순서를 검증한다.
        doAnswer(invocation -> {
            assertThat(event.getStatus()).isEqualTo(RunningEventStatus.CLOSED);
            ReflectionTestUtils.setField(event, "updatedAt", NOW);
            return null;
        }).when(runningEventRepository).flush();

        RunningEventResponseDto response = runningEventService.getRunningEvent(1L);

        assertThat(response.status()).isEqualTo(RunningEventStatus.CLOSED);
        assertThat(response.updatedAt()).isEqualTo(NOW);
        verify(runningEventRepository).flush();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    @DisplayName("목록 조회도 시작 시각 경계를 엔티티와 응답에 동일하게 적용한다")
    void listRefreshesStatusAtStartBoundary(int startOffsetSeconds) {
        RunningEvent event = eventAt(1L, NOW.plusSeconds(startOffsetSeconds));
        PageRequest pageable = PageRequest.of(0, 10);
        when(runningEventRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(event), pageable, 1));

        Page<RunningEventListResponseDto> response = runningEventService.getRunningEvents(pageable);

        RunningEventStatus expected = startOffsetSeconds <= 0
                ? RunningEventStatus.CLOSED : RunningEventStatus.RECRUITING;
        assertThat(event.getStatus()).isEqualTo(expected);
        assertThat(response.getContent()).extracting(RunningEventListResponseDto::status)
                .containsExactly(expected);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = RunningEventStatus.class, names = {"CLOSED", "CANCELED", "COMPLETED"})
    @DisplayName("상세와 목록 조회는 이미 마감되거나 개최 취소 또는 완료된 상태를 덮어쓰지 않는다")
    void readsPreserveNonRecruitingStatus(RunningEventStatus status) {
        RunningEvent event = eventAt(1L, NOW.minusDays(1));
        ReflectionTestUtils.setField(event, "status", status);
        PageRequest pageable = PageRequest.of(0, 10);
        when(runningEventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(runningEventRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(event), pageable, 1));

        assertThat(runningEventService.getRunningEvent(1L).status()).isEqualTo(status);
        assertThat(runningEventService.getRunningEvents(pageable).getContent())
                .extracting(RunningEventListResponseDto::status).containsExactly(status);
        assertThat(event.getStatus()).isEqualTo(status);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
        verify(runningEventRepository, never()).flush();
    }

    @Test
    @DisplayName("목록은 조회한 페이지에서 시작한 이벤트만 마감하고 페이징 정보와 순서를 유지한다")
    void listRefreshesOnlyReturnedPageAndPreservesPagination() {
        RunningEvent elapsed = eventAt(11L, NOW.minusMinutes(1));
        RunningEvent future = eventAt(12L, NOW.plusMinutes(1));
        RunningEvent canceled = eventAt(13L, NOW.minusMinutes(1));
        ReflectionTestUtils.setField(canceled, "status", RunningEventStatus.CANCELED);
        PageRequest pageable = PageRequest.of(1, 3);
        when(runningEventRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(elapsed, future, canceled), pageable, 8));

        Page<RunningEventListResponseDto> response = runningEventService.getRunningEvents(pageable);

        assertThat(response.getContent()).extracting(RunningEventListResponseDto::id)
                .containsExactly(11L, 12L, 13L);
        assertThat(response.getContent()).extracting(RunningEventListResponseDto::status)
                .containsExactly(RunningEventStatus.CLOSED, RunningEventStatus.RECRUITING,
                        RunningEventStatus.CANCELED);
        assertThat(elapsed.getStatus()).isEqualTo(RunningEventStatus.CLOSED);
        assertThat(future.getStatus()).isEqualTo(RunningEventStatus.RECRUITING);
        assertThat(canceled.getStatus()).isEqualTo(RunningEventStatus.CANCELED);
        assertThat(response.getTotalElements()).isEqualTo(8);
        assertThat(response.getTotalPages()).isEqualTo(3);
        assertThat(response.getNumber()).isEqualTo(1);
        verify(runningEventRepository).findAll(pageable);
        // 전체 목록을 따로 조회하지 않고 지정한 페이지의 조회만 사용한다.
        verifyNoMoreInteractions(runningEventRepository);
        verifyNoInteractions(memberRepository, runningEventParticipantRepository);
    }

    @Test
    @DisplayName("빈 페이지는 추가 마감 대상을 조회하지 않고 그대로 반환한다")
    void emptyPageReturnsWithoutAdditionalQueries() {
        PageRequest pageable = PageRequest.of(0, 10);
        when(runningEventRepository.findAll(pageable)).thenReturn(Page.empty(pageable));

        Page<RunningEventListResponseDto> response = runningEventService.getRunningEvents(pageable);

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isZero();
        verify(runningEventRepository).findAll(pageable);
        verifyNoMoreInteractions(runningEventRepository);
    }

    @Test
    @DisplayName("상세 조회 후 목록과 상세를 재조회해도 마감 상태와 참가 인원이 유지된다")
    void repeatedReadsKeepClosedStateAndParticipantCount() {
        RunningEvent event = eventAt(1L, NOW);
        PageRequest pageable = PageRequest.of(0, 10);
        when(runningEventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(runningEventRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(event), pageable, 1));

        assertThat(runningEventService.getRunningEvent(1L).status()).isEqualTo(RunningEventStatus.CLOSED);
        assertThat(runningEventService.getRunningEvents(pageable).getContent())
                .extracting(RunningEventListResponseDto::status).containsExactly(RunningEventStatus.CLOSED);
        assertThat(runningEventService.getRunningEvent(1L).status()).isEqualTo(RunningEventStatus.CLOSED);
        assertThat(event.getCurrentParticipantCount()).isEqualTo(1);
    }

    private RunningEvent eventAt(Long id, LocalDateTime startAt) {
        RunningEvent event = RunningEventMapper.toEntity(createRequest(), createMember());
        ReflectionTestUtils.setField(event, "id", id);
        ReflectionTestUtils.setField(event, "startAt", startAt);
        return event;
    }

    private RunningEventCreateRequestDto createRequest() {
        return new RunningEventCreateRequestDto(
                "한강 야간 러닝",
                "함께 가볍게 달려요.",
                "서울",
                "한강공원",
                "여의나루역 2번 출구",
                NOW.plusDays(7),
                360,
                BigDecimal.valueOf(5),
                10
        );
    }

    private Member createMember() {
        return Member.builder()
                .email("runner@example.com")
                .password("encoded-password")
                .name("홍길동")
                .nickname("밤빛폭격기")
                .region("서울")
                .averagePaceSeconds(360)
                .preferredDistanceKm(BigDecimal.valueOf(5))
                .build();
    }
}
