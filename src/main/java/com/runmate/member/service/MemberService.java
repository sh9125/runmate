package com.runmate.member.service;

import com.runmate.member.dto.request.MemberSignupRequestDto;
import com.runmate.member.dto.response.MemberResponseDto;
import com.runmate.member.entity.Member;
import com.runmate.member.mapper.MemberMapper;
import com.runmate.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public MemberResponseDto signup(MemberSignupRequestDto memberSignupRequestDto) {
        if (memberRepository.existsByEmail(memberSignupRequestDto.getEmail())) {
            throw new IllegalArgumentException("이미 사용 중인 이메일 입니다.");
        }

        if (memberRepository.existsByNickname(memberSignupRequestDto.getNickname())) {
            throw new IllegalArgumentException("이미 사용 중인 닉네임 입니다.");
        }

        String encodedPassword = passwordEncoder.encode(memberSignupRequestDto.getPassword());

        Member member = MemberMapper.toEntity(memberSignupRequestDto, encodedPassword);
        Member savedMember = memberRepository.save(member);

        return MemberMapper.toResponse(savedMember);
    }

}
