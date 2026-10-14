package com.runmate.runningevent.service;

import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import com.runmate.runningevent.dto.request.RunningEventCreateRequestDto;
import com.runmate.runningevent.dto.response.RunningEventResponseDto;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventStatus;
import com.runmate.runningevent.repository.RunningEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
        verify(runningEventRepository).save(any(RunningEvent.class));


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
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("존재하지 않는 회원입니다.");

        verify(runningEventRepository, never()).save(any(RunningEvent.class));
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
