package com.runmate.auth.controller;

import com.runmate.auth.dto.request.LoginRequestDto;
import com.runmate.auth.dto.response.LoginResponseDto;
import com.runmate.auth.service.AuthService;
import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final String REFRESH_COOKIE = "runmate_refresh";
    public static final String AUTH_REQUEST_HEADER = "X-Runmate-Auth";
    private final AuthService authService;

    @Value("${auth.refresh-cookie-secure:true}")
    private boolean secureCookie;

    @PostMapping(path = "/login", consumes = "application/json")
    public ResponseEntity<LoginResponseDto> login(
            @Valid @RequestBody LoginRequestDto loginRequestDto,
            @CookieValue(name = REFRESH_COOKIE, required = false) String previousRefreshToken) {
        AuthService.LoginResult result = authService.login(loginRequestDto, previousRefreshToken);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken(), result.ttl()).toString())
                .body(result.response());
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponseDto> refresh(
            @RequestHeader(name = AUTH_REQUEST_HEADER, required = false) String authRequest,
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        requireAuthRequest(authRequest);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(authService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(name = AUTH_REQUEST_HEADER, required = false) String authRequest,
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        requireAuthRequest(authRequest);
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .build();
    }

    // 일반 폼 전송으로는 이 헤더를 붙일 수 없고, 다른 출처의 스크립트는 CORS 사전 검사를 거친다.
    private void requireAuthRequest(String header) {
        if (!"1".equals(header)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true).secure(secureCookie).sameSite("Lax")
                .path("/api/v1/auth").maxAge(maxAge).build();
    }

    @GetMapping("/me")
    public ResponseEntity<String> me(Authentication authentication) {
        return ResponseEntity.ok(authentication.getName());
    }
}
