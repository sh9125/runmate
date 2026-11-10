package com.runmate.member.dto.request;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class MemberUpdateRequestDto {
    @NotBlank @Size(max = 30)
    private String name;
    @NotBlank @Size(max = 30)
    private String nickname;
    @NotBlank @Size(max = 50)
    private String region;
    @NotNull @Min(180) @Max(900)
    private Integer averagePaceSeconds;
    @NotNull @DecimalMin("1.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2)
    private BigDecimal preferredDistanceKm;
    @Size(min = 8, max = 100)
    private String currentPassword;
    private String newPassword;
}
