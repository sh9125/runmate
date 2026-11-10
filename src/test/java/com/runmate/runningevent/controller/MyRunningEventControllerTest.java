package com.runmate.runningevent.controller;

import com.runmate.auth.jwt.JwtAuthenticationFilter;
import com.runmate.auth.jwt.JwtTokenProvider;
import com.runmate.global.config.SecurityConfig;
import com.runmate.global.exception.BusinessException;
import com.runmate.global.exception.ErrorCode;
import com.runmate.global.exception.GlobalExceptionHandler;
import com.runmate.global.exception.SecurityErrorResponseWriter;
import com.runmate.member.repository.MemberRepository;
import com.runmate.runningevent.dto.response.MyRunningEventApplicationStatus;
import com.runmate.runningevent.dto.response.MyRunningEventEndReason;
import com.runmate.runningevent.dto.response.MyRunningEventResponseDto;
import com.runmate.runningevent.dto.response.RunningEventListResponseDto;
import com.runmate.runningevent.entity.RunningEventStatus;
import com.runmate.runningevent.service.MyRunningEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 MVC·보안 설정을 사용하며 서비스와 JWT 의존성은 Mock으로 대체한다. */
@WebMvcTest(MyRunningEventController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, SecurityErrorResponseWriter.class,
        GlobalExceptionHandler.class})
@WithMockUser(username = "runner@example.com")
class MyRunningEventControllerTest {
    private static final String BASE = "/api/v1/members/me/running-events";
    private static final String EMAIL = "runner@example.com";

    @Autowired MockMvc mockMvc;
    @MockitoBean MyRunningEventService service;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean MemberRepository memberRepository;
    // 웹 테스트에서는 JPA Auditing의 DB 메타모델 의존성을 대체한다.
    @MockitoBean JpaMetamodelMappingContext jpaMappingContext;

    @Test
    void createdEventsUseAuthenticatedMemberAndRequestedPage() throws Exception {
        PageRequest page = PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "startAt"));
        when(service.getCreatedEvents(EMAIL, page))
                .thenReturn(new PageImpl<>(List.of(event()), page, 5));

        mockMvc.perform(get(BASE + "/created")
                        .param("page", "1").param("size", "2").param("sort", "startAt,desc")
                        .param("memberId", "999").param("email", "other@example.com"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content[0].id").value(8))
                .andExpect(jsonPath("$.content[0].creatorNickname").value("러너A"))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5));
        verify(service).getCreatedEvents(EMAIL, page);
    }

    @Test
    void participatingEventsReturnApplicationStateAndDefaultPage() throws Exception {
        PageRequest page = PageRequest.of(0, 20);
        when(service.getParticipatingEvents(EMAIL, page)).thenReturn(new PageImpl<>(List.of(
                new MyRunningEventResponseDto(event(), MyRunningEventApplicationStatus.PARTICIPATING,
                        null, null)), page, 1));

        mockMvc.perform(get(BASE + "/participating"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].event.id").value(8))
                .andExpect(jsonPath("$.content[0].applicationStatus").value("PARTICIPATING"))
                .andExpect(jsonPath("$.content[0].endReason").doesNotExist())
                .andExpect(jsonPath("$.content[0].waitlistPosition").doesNotExist());
        verify(service).getParticipatingEvents(EMAIL, page);
    }

    @Test
    void waitingEventsReturnCurrentPosition() throws Exception {
        when(service.getWaitingEvents(eq(EMAIL), any())).thenReturn(new PageImpl<>(List.of(
                new MyRunningEventResponseDto(event(), MyRunningEventApplicationStatus.WAITING,
                        null, 2L))));

        mockMvc.perform(get(BASE + "/waiting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].applicationStatus").value("WAITING"))
                .andExpect(jsonPath("$.content[0].waitlistPosition").value(2))
                .andExpect(jsonPath("$.content[0].endReason").doesNotExist());
    }

    @ParameterizedTest
    @EnumSource(MyRunningEventEndReason.class)
    void endedEventsReturnEachReason(MyRunningEventEndReason reason) throws Exception {
        when(service.getEndedEvents(eq(EMAIL), any())).thenReturn(new PageImpl<>(List.of(
                new MyRunningEventResponseDto(event(), MyRunningEventApplicationStatus.ENDED,
                        reason, null))));

        mockMvc.perform(get(BASE + "/ended"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].applicationStatus").value("ENDED"))
                .andExpect(jsonPath("$.content[0].endReason").value(reason.name()))
                .andExpect(jsonPath("$.content[0].waitlistPosition").doesNotExist());
    }

    @Test
    void emptyPagePreservesTotalAndRequestedPage() throws Exception {
        PageRequest page = PageRequest.of(3, 2);
        when(service.getWaitingEvents(EMAIL, page)).thenReturn(new PageImpl<>(List.of(), page, 5));

        mockMvc.perform(get(BASE + "/waiting").param("page", "3").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.number").value(3))
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"created", "participating", "waiting", "ended"})
    @WithAnonymousUser
    void allListsRequireAuthentication(String list) throws Exception {
        mockMvc.perform(get(BASE + "/" + list))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void missingMemberUsesCommonErrorResponse() throws Exception {
        when(service.getEndedEvents(eq(EMAIL), any()))
                .thenThrow(new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        mockMvc.perform(get(BASE + "/ended"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
    }

    private RunningEventListResponseDto event() {
        return new RunningEventListResponseDto(8L, "한강 러닝", "서울", "한강",
                LocalDateTime.of(2030, 11, 1, 20, 0), 360, BigDecimal.valueOf(5),
                10, 2, RunningEventStatus.RECRUITING, "러너A");
    }
}
