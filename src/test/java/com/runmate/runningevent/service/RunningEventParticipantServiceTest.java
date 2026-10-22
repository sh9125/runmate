package com.runmate.runningevent.service;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;

import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import com.runmate.runningevent.dto.request.RunningEventCreateRequestDto;
import com.runmate.runningevent.dto.response.RunningEventParticipantResponseDto;
import com.runmate.runningevent.entity.RunningEvent;
import com.runmate.runningevent.entity.RunningEventParticipant;
import com.runmate.runningevent.repository.RunningEventParticipantRepository;
import com.runmate.runningevent.repository.RunningEventRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RunningEventParticipantServiceTest {
    @Mock
    private MemberRepository memberRepository;

    @Mock
    private RunningEventRepository runningEventRepository;

    @Mock
    private RunningEventParticipantRepository runningEventParticipantRepository;

    @InjectMocks
    private  RunningEventParticipantService runningEventParticipantService;

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
        when(runningEventParticipantRepository.existsByRunningEvent_IdAndMember_Id(runningEvent.getId(), member.getId()))
                .thenReturn(false);
        when(runningEventParticipantRepository.save(any(RunningEventParticipant.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0, RunningEventParticipant.class));

        //when
        RunningEventParticipantResponseDto response = runningEventParticipantService.participateRunningEvent(runningEvent.getId(), member.getEmail());

        //then
        assertThat(response.eventId()).isEqualTo(runningEvent.getId());
        assertThat(response.memberId()).isEqualTo(member.getId());
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
        when(runningEventParticipantRepository.existsByRunningEvent_IdAndMember_Id(runningEvent.getId(), member.getId()))
                .thenReturn(true);

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
                .startAt(LocalDateTime.of(2026, 11, 1, 19, 0))
                .preferredPaceSeconds(360)
                .targetDistanceKm(BigDecimal.valueOf(5))
                .capacity(10)
                .creator(creator)
                .build();
    }
}
