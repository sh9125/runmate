package com.runmate.global.exception;

import com.runmate.auth.controller.AuthController;
import com.runmate.auth.jwt.JwtAuthenticationFilter;
import com.runmate.auth.jwt.JwtTokenProvider;
import com.runmate.auth.service.AuthService;
import com.runmate.global.config.SecurityConfig;
import com.runmate.member.controller.MemberController;
import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import com.runmate.member.service.MemberService;
import com.runmate.runningevent.controller.RunningEventController;
import com.runmate.runningevent.dto.response.RunningEventResponseDto;
import com.runmate.runningevent.entity.RunningEventStatus;
import com.runmate.runningevent.service.RunningEventParticipantService;
import com.runmate.runningevent.service.RunningEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({RunningEventController.class, MemberController.class, AuthController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, SecurityErrorResponseWriter.class,
        GlobalExceptionHandler.class})
@WithMockUser(username = "runner@example.com")
class ApiExceptionHandlingTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RunningEventService runningEventService;
    @MockitoBean
    private RunningEventParticipantService participantService;
    @MockitoBean
    private MemberService memberService;
    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;
    @MockitoBean
    private MemberRepository memberRepository;
    // 메인 설정의 JPA Auditing이 웹 테스트에서 DB 메타모델을 요구하지 않도록 대체한다.
    @MockitoBean
    private JpaMetamodelMappingContext jpaMappingContext;

    @Test
    @DisplayName("없는 이벤트 조회는 404와 식별 가능한 오류 코드를 반환한다")
    void eventNotFound() throws Exception {
        when(runningEventService.getRunningEvent(999L))
                .thenThrow(new BusinessException(ErrorCode.RUNNING_EVENT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/running-events/999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RUNNING_EVENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("존재하지 않는 러닝 이벤트입니다."))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    @DisplayName("중복 참가는 409를 반환한다")
    void duplicateParticipation() throws Exception {
        when(participantService.participateRunningEvent(1L, "runner@example.com"))
                .thenThrow(new BusinessException(ErrorCode.ALREADY_PARTICIPATING));

        mockMvc.perform(post("/api/v1/running-events/1/participants"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PARTICIPATING"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("중복 이메일 회원가입은 409를 반환한다")
    void duplicateSignup() throws Exception {
        when(memberService.signup(any())).thenThrow(new BusinessException(ErrorCode.DUPLICATE_EMAIL));

        mockMvc.perform(post("/api/v1/members/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"runner@example.com","password":"password123",
                                 "name":"러너","nickname":"runner","region":"서울",
                                 "averagePaceSeconds":360,"preferredDistanceKm":5}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("로그인 실패는 401을 반환한다")
    void invalidCredentials() throws Exception {
        when(authService.login(any())).thenThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""" 
                                {"email":"runner@example.com","password":"password123"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("입력 검증 실패는 필드명을 반환하고 비밀번호 입력값은 노출하지 않는다")
    void validationFailure() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"invalid-email","password":"secret"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());

        verifyNoInteractions(authService);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("깨진 JSON은 서버 오류가 아니라 400이다")
    void malformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("숫자가 아닌 이벤트 ID는 400이다")
    void invalidPathVariable() throws Exception {
        mockMvc.perform(get("/api/v1/running-events/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(runningEventService);
    }

    @Test
    @DisplayName("허용되지 않은 메서드는 405와 Allow 헤더를 보존한다")
    void unsupportedMethod() throws Exception {
        mockMvc.perform(delete("/api/v1/running-events/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("지원하지 않는 요청 Content-Type은 415이다")
    void unsupportedMediaType() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.TEXT_PLAIN).content("login"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("매핑되지 않은 경로도 공통 형식의 404를 반환한다")
    void unknownRoute() throws Exception {
        mockMvc.perform(get("/api/v1/no-such-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("인증 없는 요청은 Security에서 공통 형식의 401을 반환한다")
    void unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/running-events"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."))
                .andExpect(jsonPath("$.errors").isEmpty());
        verifyNoInteractions(runningEventService);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("잘못된 토큰은 401을 반환한다")
    void invalidToken() throws Exception {
        when(jwtTokenProvider.validateToken("invalid-token")).thenReturn(false);
        mockMvc.perform(get("/api/v1/running-events").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(runningEventService);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("유효한 토큰이라도 회원이 없으면 500 대신 401을 반환한다")
    void tokenWithMissingMember() throws Exception {
        when(jwtTokenProvider.validateToken("valid-token")).thenReturn(true);
        when(jwtTokenProvider.getEmail("valid-token")).thenReturn("deleted@example.com");
        when(memberRepository.findByEmail("deleted@example.com")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/running-events").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(runningEventService);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("유효한 토큰과 회원이 있으면 기존처럼 인증 이메일을 사용할 수 있다")
    void validTokenAuthenticatesMember() throws Exception {
        when(jwtTokenProvider.validateToken("valid-token")).thenReturn(true);
        when(jwtTokenProvider.getEmail("valid-token")).thenReturn("runner@example.com");
        when(memberRepository.findByEmail("runner@example.com"))
                .thenReturn(Optional.of(Member.builder().email("runner@example.com").build()));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("runner@example.com"));
    }

    @Test
    @DisplayName("접근 거부는 403이며 내부 메시지를 노출하지 않는다")
    void accessDenied() throws Exception {
        when(runningEventService.getRunningEvent(1L)).thenThrow(new AccessDeniedException("internal policy"));
        mockMvc.perform(get("/api/v1/running-events/1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(content().string(not(containsString("internal policy"))));
    }

    @Test
    @DisplayName("알 수 없는 서버 오류는 500이며 내부 정보를 노출하지 않는다")
    void unexpectedException() throws Exception {
        when(runningEventService.getRunningEvent(1L))
                .thenThrow(new IllegalStateException("sensitive SQL detail"));
        mockMvc.perform(get("/api/v1/running-events/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(content().string(not(containsString("sensitive SQL detail"))));
    }

    @Test
    @DisplayName("일반 IllegalArgumentException을 업무 중복으로 단정하지 않는다")
    void arbitraryIllegalArgumentException() throws Exception {
        when(runningEventService.getRunningEvent(1L)).thenThrow(new IllegalArgumentException("programming error"));
        mockMvc.perform(get("/api/v1/running-events/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }

    @Test
    @DisplayName("이벤트 전체 수정은 인증 이메일을 전달하고 수정 결과를 반환한다")
    void updateEvent() throws Exception {
        when(runningEventService.updateRunningEvent(eq(1L), any(), eq("runner@example.com")))
                .thenReturn(eventResponse(RunningEventStatus.RECRUITING));

        mockMvc.perform(put("/api/v1/running-events/1")
                        .contentType(MediaType.APPLICATION_JSON).content(validUpdateJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("수정 제목"));
    }

    @Test
    @DisplayName("개최 취소는 본문 없이 요청하고 취소 상태를 반환한다")
    void cancelEvent() throws Exception {
        when(runningEventService.cancelRunningEvent(1L, "runner@example.com"))
                .thenReturn(eventResponse(RunningEventStatus.CANCELED));

        mockMvc.perform(post("/api/v1/running-events/1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.currentParticipantCount").value(1));
    }

    @Test
    @DisplayName("다른 회원의 이벤트 수정은 403으로 응답한다")
    void updateRequiresCreator() throws Exception {
        when(runningEventService.updateRunningEvent(eq(1L), any(), eq("runner@example.com")))
                .thenThrow(new BusinessException(ErrorCode.NOT_EVENT_CREATOR));

        mockMvc.perform(put("/api/v1/running-events/1")
                        .contentType(MediaType.APPLICATION_JSON).content(validUpdateJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_EVENT_CREATOR"));
    }

    @Test
    @DisplayName("수정과 개최 취소의 마감 이후 요청은 공통 형식의 409로 응답한다")
    void managementDeadlineConflict() throws Exception {
        when(runningEventService.updateRunningEvent(eq(1L), any(), eq("runner@example.com")))
                .thenThrow(new BusinessException(ErrorCode.EVENT_MANAGEMENT_DEADLINE_PASSED));
        when(runningEventService.cancelRunningEvent(1L, "runner@example.com"))
                .thenThrow(new BusinessException(ErrorCode.EVENT_MANAGEMENT_DEADLINE_PASSED));

        mockMvc.perform(put("/api/v1/running-events/1")
                        .contentType(MediaType.APPLICATION_JSON).content(validUpdateJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_MANAGEMENT_DEADLINE_PASSED"));
        mockMvc.perform(post("/api/v1/running-events/1/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_MANAGEMENT_DEADLINE_PASSED"));
    }

    @Test
    @DisplayName("필수 수정값이 빠지거나 정원이 0이면 400이고 Service를 호출하지 않는다")
    void updateValidationFailure() throws Exception {
        mockMvc.perform(put("/api/v1/running-events/1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("startAt")));
        mockMvc.perform(put("/api/v1/running-events/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateJson().replace("\"capacity\":10", "\"capacity\":0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("capacity")));
        verifyNoInteractions(runningEventService);
    }

    @Test
    @WithAnonymousUser
    @DisplayName("인증 없이 수정과 개최 취소를 할 수 없다")
    void managementRequiresAuthentication() throws Exception {
        mockMvc.perform(put("/api/v1/running-events/1")
                        .contentType(MediaType.APPLICATION_JSON).content(validUpdateJson()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/running-events/1/cancel"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(runningEventService);
    }

    private String validUpdateJson() {
        return """
                {"title":"수정 제목","description":"설명","region":"서울",
                 "runningPlace":"한강공원","meetingPlace":"정문","startAt":"2030-11-01T19:00:00",
                 "preferredPaceSeconds":360,"targetDistanceKm":5,"capacity":10}
                """;
    }

    private RunningEventResponseDto eventResponse(RunningEventStatus status) {
        return new RunningEventResponseDto(1L, "수정 제목", "설명", "서울", "한강공원", "정문",
                LocalDateTime.of(2030, 11, 1, 19, 0), 360, BigDecimal.valueOf(5), 10, 1,
                status, 2L, "생성자", null, null);
    }
}
