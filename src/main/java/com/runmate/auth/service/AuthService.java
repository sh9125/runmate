package com.runmate.auth.service;

import com.runmate.auth.dto.request.LoginRequestDto;
import com.runmate.auth.dto.response.LoginResponseDto;
import com.runmate.auth.entity.RefreshSession;
import com.runmate.auth.jwt.JwtTokenProvider;
import com.runmate.auth.repository.RefreshSessionRepository;
import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {
    private final MemberRepository memberRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final RefreshSessionRepository refreshSessions;
    private final Clock clock;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${auth.refresh-token-ttl-seconds:604800}")
    private long refreshTokenTtlSeconds;

    @Transactional
    public LoginResult login(LoginRequestDto loginRequestDto, String previousRefreshToken) {
        Member member = memberRepository.findByEmail(loginRequestDto.getEmail())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        if (!passwordEncoder.matches(loginRequestDto.getPassword(), member.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        revoke(previousRefreshToken);
        Instant now = clock.instant();
        Duration ttl = Duration.ofSeconds(refreshTokenTtlSeconds);
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("갱신 토큰 유효 기간은 양수여야 합니다.");
        }
        refreshSessions.deleteExpiredForMember(member.getId(), now);
        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        refreshSessions.save(new RefreshSession(hash(refreshToken), member, now.plus(ttl)));
        return new LoginResult(accessTokenFor(member), refreshToken, ttl);
    }

    @Transactional
    public LoginResponseDto refresh(String refreshToken) {
        if (!hasTokenFormat(refreshToken)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        // 로그아웃의 삭제와 갱신이 같은 세션을 동시에 처리할 때 순서를 확정한다.
        RefreshSession session = refreshSessions.findForRefresh(hash(refreshToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (!session.getExpiresAt().isAfter(clock.instant())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return accessTokenFor(session.getMember());
    }

    @Transactional
    public void logout(String refreshToken) {
        revoke(refreshToken);
    }

    private void revoke(String refreshToken) {
        if (hasTokenFormat(refreshToken)) {
            refreshSessions.revoke(hash(refreshToken));
        }
    }

    private boolean hasTokenFormat(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }

    private LoginResponseDto accessTokenFor(Member member) {
        return new LoginResponseDto(jwtTokenProvider.createAccessToken(member.getEmail()), "Bearer");
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    public record LoginResult(LoginResponseDto response, String refreshToken, Duration ttl) {
    }

}
