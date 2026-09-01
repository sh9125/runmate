package com.runmate.member.service;

import com.runmate.member.dto.request.MemberSignupRequestDto;
import com.runmate.member.dto.response.MemberResponseDto;
import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private MemberService memberService;

    @Test
    @DisplayName("회원가입 성공 시 비밀번호를 암호화한 뒤 회원을 저장한다")
    void signupSuccess() {
        MemberSignupRequestDto request = createSignupRequest();
        when(memberRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(memberRepository.existsByNickname(request.getNickname())).thenReturn(false);
        when(passwordEncoder.encode(request.getPassword())).thenReturn("encoded-password");
        when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MemberResponseDto response = memberService.signup(request);

        ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(memberCaptor.capture());
        Member savedMember = memberCaptor.getValue();

        assertThat(savedMember.getPassword()).isEqualTo("encoded-password");
        assertThat(response.email()).isEqualTo(request.getEmail());
        assertThat(response.nickname()).isEqualTo(request.getNickname());
    }

    @Test
    @DisplayName("이미 사용 중인 이메일이면 회원가입에 실패한다")
    void signupFailByDuplicateEmail() {
        MemberSignupRequestDto request = createSignupRequest();
        when(memberRepository.existsByEmail(request.getEmail())).thenReturn(true);

        assertThatThrownBy(() -> memberService.signup(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이미 사용 중인 이메일 입니다.");

        verify(memberRepository, never()).save(any(Member.class));
    }

    @Test
    @DisplayName("이미 사용 중인 닉네임이면 회원가입에 실패한다")
    void signupFailByDuplicateNickname() {
        MemberSignupRequestDto request = createSignupRequest();
        when(memberRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(memberRepository.existsByNickname(request.getNickname())).thenReturn(true);

        assertThatThrownBy(() -> memberService.signup(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이미 사용 중인 닉네임 입니다.");

        verify(memberRepository, never()).save(any(Member.class));
    }

    private MemberSignupRequestDto createSignupRequest() {
        return new MemberSignupRequestDto(
                "runner@example.com",
                "password123",
                "홍길동",
                "night-runner",
                "서울 송파구",
                330,
                BigDecimal.valueOf(5.00)
        );
    }
}
