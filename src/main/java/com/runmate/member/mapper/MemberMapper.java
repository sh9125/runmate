package com.runmate.member.mapper;

import com.runmate.member.dto.request.MemberSignupRequestDto;
import com.runmate.member.dto.response.MemberResponseDto;
import com.runmate.member.entity.Member;

public class MemberMapper {
    private MemberMapper() {

    }

    public static Member toEntity(MemberSignupRequestDto request, String encodedPassword) {
        return Member.builder()
                .email(request.getEmail())
                .password(encodedPassword)
                .name(request.getName())
                .nickname(request.getNickname())
                .region(request.getRegion())
                .averagePaceSeconds(request.getAveragePaceSeconds())
                .preferredDistanceKm(request.getPreferredDistanceKm())
                .build();
    }

    public static MemberResponseDto toResponse(Member member) {
        return new MemberResponseDto(
                member.getEmail(),
                member.getName(),
                member.getNickname(),
                member.getRegion(),
                member.getAveragePaceSeconds(),
                member.getPreferredDistanceKm()
        );
    }
}
