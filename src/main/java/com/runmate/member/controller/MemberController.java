package com.runmate.member.controller;

import com.runmate.member.dto.request.MemberSignupRequestDto;
import com.runmate.member.dto.response.MemberResponseDto;
import com.runmate.member.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members")
public class MemberController {
    private final MemberService memberService;

    @PostMapping("/signup")
    public ResponseEntity<MemberResponseDto> signup(
            @Valid @RequestBody MemberSignupRequestDto request
            ) {
        MemberResponseDto response = memberService.signup(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);

    }
}
