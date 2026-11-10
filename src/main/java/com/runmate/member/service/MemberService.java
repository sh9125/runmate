package com.runmate.member.service;

import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.member.dto.request.MemberSignupRequestDto;
import com.runmate.member.dto.request.MemberUpdateRequestDto;
import com.runmate.member.dto.response.MemberResponseDto;
import com.runmate.member.entity.Member;
import com.runmate.member.mapper.MemberMapper;
import com.runmate.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public MemberResponseDto getMember(String email) {
        return memberRepository.findByEmail(email)
                .map(MemberMapper::toResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    @Transactional
    public MemberResponseDto signup(MemberSignupRequestDto memberSignupRequestDto) {
        validateNewPassword(memberSignupRequestDto.getPassword());
        if (memberRepository.existsByEmail(memberSignupRequestDto.getEmail())) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        if (memberRepository.existsByNickname(memberSignupRequestDto.getNickname())) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }

        String encodedPassword = passwordEncoder.encode(memberSignupRequestDto.getPassword());

        Member member = MemberMapper.toEntity(memberSignupRequestDto, encodedPassword);
        Member savedMember = memberRepository.save(member);

        return MemberMapper.toResponse(savedMember);
    }

    @Transactional
    public MemberResponseDto updateProfile(String email, MemberUpdateRequestDto request) {
        Member member = memberRepository.findByEmailForUpdate(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (!member.getNickname().equals(request.getNickname()) && memberRepository.existsByNickname(request.getNickname())) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }
        String currentPassword = request.getCurrentPassword();
        String newPassword = request.getNewPassword();
        if (currentPassword != null || newPassword != null) {
            if (currentPassword == null || currentPassword.isBlank() || newPassword == null) {
                throw new BusinessException(ErrorCode.PASSWORD_CHANGE_FIELDS_REQUIRED);
            }
            validateNewPassword(newPassword);
            if (!passwordEncoder.matches(currentPassword, member.getPassword())) {
                throw new BusinessException(ErrorCode.INCORRECT_CURRENT_PASSWORD);
            }
            member.changePassword(passwordEncoder.encode(newPassword));
        }
        member.updateProfile(request.getName(), request.getNickname(), request.getRegion(), request.getAveragePaceSeconds(),
                request.getPreferredDistanceKm());
        return MemberMapper.toResponse(member);
    }

    private void validateNewPassword(String password) {
        if (password == null || password.isBlank() || password.length() < 8) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_SHORT);
        }
        // BCrypt 구현이 끝에 붙이는 종료 바이트를 포함해 최대 72바이트다.
        if (password.getBytes(StandardCharsets.UTF_8).length > 71) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_LONG);
        }
    }

}
