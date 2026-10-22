package com.runmate.auth.service;

import com.runmate.auth.dto.request.LoginRequestDto;
import com.runmate.auth.dto.response.LoginResponseDto;
import com.runmate.auth.jwt.JwtTokenProvider;
import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @InjectMocks
    private AuthService authService;

    @Test
    void missingMemberDoesNotIssueToken() {
        LoginRequestDto request = request();
        when(memberRepository.findByEmail(request.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    void wrongPasswordDoesNotIssueToken() {
        LoginRequestDto request = request();
        Member member = Member.builder().email(request.getEmail()).password("encoded-password").build();
        when(memberRepository.findByEmail(request.getEmail())).thenReturn(Optional.of(member));
        when(passwordEncoder.matches(request.getPassword(), member.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test
    void successfulLoginStillIssuesToken() {
        LoginRequestDto request = request();
        Member member = Member.builder().email(request.getEmail()).password("encoded-password").build();
        when(memberRepository.findByEmail(request.getEmail())).thenReturn(Optional.of(member));
        when(passwordEncoder.matches(request.getPassword(), member.getPassword())).thenReturn(true);
        when(jwtTokenProvider.createAccessToken(request.getEmail())).thenReturn("access-token");

        LoginResponseDto response = authService.login(request);

        assertThat(response).isEqualTo(new LoginResponseDto("access-token", "Bearer"));
    }

    private LoginRequestDto request() {
        LoginRequestDto request = new LoginRequestDto();
        ReflectionTestUtils.setField(request, "email", "runner@example.com");
        ReflectionTestUtils.setField(request, "password", "password123");
        return request;
    }
}
