package com.runmate.auth.service;

import com.runmate.auth.dto.request.LoginRequestDto;
import com.runmate.auth.dto.response.LoginResponseDto;
import com.runmate.auth.jwt.JwtTokenProvider;
import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {
    private final MemberRepository memberRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    public LoginResponseDto login(LoginRequestDto loginRequestDto) {
        Member member = memberRepository.findByEmail(loginRequestDto.getEmail()).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다."));
        if (!passwordEncoder.matches(loginRequestDto.getPassword(),member.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
            }
        String accessToken = jwtTokenProvider.createAccessToken(loginRequestDto.getEmail());
        return new LoginResponseDto(accessToken, "Bearer");
    }

}
