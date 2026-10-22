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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RunningEventServiceTest {
    @Mock
    private RunningEventRepository  runningEventRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private RunningEventParticipantRepository runningEventParticipantRepository;

    @InjectMocks
    private RunningEventService runningEventService;

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

    private RunningEventCreateRequestDto createRequest() {
        return new RunningEventCreateRequestDto(
                "한강 야간 러닝",
                "함께 가볍게 달려요.",
                "서울",
                "한강공원",
                "여의나루역 2번 출구",
                LocalDateTime.now().plusDays(7),
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
