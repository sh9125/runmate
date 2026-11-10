package com.runmate.member.controller;

import com.runmate.member.dto.request.MemberSignupRequestDto;
import com.runmate.member.dto.request.MemberUpdateRequestDto;
import com.runmate.member.dto.response.MemberResponseDto;
import com.runmate.member.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members")
public class MemberController {
    private final MemberService memberService;

    @GetMapping("/me")
    public ResponseEntity<MemberResponseDto> me(Authentication authentication) {
        return ResponseEntity.ok(memberService.getMember(authentication.getName()));
    }

    @PutMapping("/me")
    public ResponseEntity<MemberResponseDto> updateProfile(
            @Valid @RequestBody MemberUpdateRequestDto request, Authentication authentication) {
        return ResponseEntity.ok(memberService.updateProfile(authentication.getName(), request));
    }

    @PostMapping("/signup")
    public ResponseEntity<MemberResponseDto> signup(
            @Valid @RequestBody MemberSignupRequestDto request
            ) {
        MemberResponseDto response = memberService.signup(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);

    }
}
