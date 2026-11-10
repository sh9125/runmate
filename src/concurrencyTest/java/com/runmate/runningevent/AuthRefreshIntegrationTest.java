package com.runmate.runningevent;

import com.runmate.member.entity.Member;
import com.runmate.member.repository.MemberRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("concurrency")
@ContextConfiguration(initializers = ParticipationRaceTest.IsolatedDatabaseGuard.class)
@Transactional
class AuthRefreshIntegrationTest {
    private static final String AUTH = "/api/v1/auth";
    private static final String ORIGIN = "http://localhost:5173";
    private static final String HEADER = "X-Runmate-Auth";
    private static final String COOKIE = "runmate_refresh";
    private static final String EMAIL = "refresh@auth.test";
    private static final String PASSWORD = "password123";

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    @BeforeEach
    void createMember() {
        members.save(Member.builder().email(EMAIL).password(passwordEncoder.encode(PASSWORD))
                .name("러너").nickname("refresh-test").region("서울").averagePaceSeconds(360)
                .preferredDistanceKm(BigDecimal.valueOf(5)).build());
    }

    @Test
    void cookieRestoresLoginAndLogoutRevokesIt() throws Exception {
        Cookie cookie = login();
        entityManager.flush();
        assertThat(jdbc.queryForObject("select token_hash from auth_refresh_sessions", String.class))
                .matches("[0-9a-f]{64}").isNotEqualTo(cookie.getValue());

        mvc.perform(post(AUTH + "/refresh").header(HttpHeaders.ORIGIN, ORIGIN).cookie(cookie))
                .andExpect(status().isForbidden());
        mvc.perform(post(AUTH + "/refresh").header(HttpHeaders.ORIGIN, "https://other.example")
                        .header(HEADER, "1").cookie(cookie))
                .andExpect(status().isForbidden());

        String refreshResponse = mvc.perform(post(AUTH + "/refresh").header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HEADER, "1").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        String accessToken = json.readTree(refreshResponse).get("accessToken").asString();
        mvc.perform(get(AUTH + "/me").header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(content().string(EMAIL));

        mvc.perform(post(AUTH + "/logout").header(HttpHeaders.ORIGIN, ORIGIN).header(HEADER, "1").cookie(cookie))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        mvc.perform(post(AUTH + "/refresh").header(HEADER, "1").cookie(cookie))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void profileChangesKeepEmailAndRequireCurrentPassword() throws Exception {
        final String profilePath = "/api/v1/members/me";
        String loginResponse = mvc.perform(post(AUTH + "/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("email", EMAIL, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + json.readTree(loginResponse).get("accessToken").asString();
        String profile = """
                {"email":"other@auth.test","name":"새 이름","nickname":"new-runner","region":"부산",
                 "averagePaceSeconds":390,"preferredDistanceKm":7.25}
                """;
        mvc.perform(put(profilePath).contentType(MediaType.APPLICATION_JSON).content(profile))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(profilePath).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(profile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.nickname").value("new-runner"))
                .andExpect(jsonPath("$.password").doesNotExist());
        entityManager.flush();
        entityManager.clear();
        Member updated = members.findByEmail(EMAIL).orElseThrow();
        assertThat(updated.getName()).isEqualTo("새 이름");
        assertThat(updated.getRegion()).isEqualTo("부산");
        assertThat(updated.getAveragePaceSeconds()).isEqualTo(390);
        assertThat(updated.getPreferredDistanceKm()).isEqualByComparingTo("7.25");
        assertThat(passwordEncoder.matches(PASSWORD, updated.getPassword())).isTrue();

        String passwordChange = """
                {"name":"새 이름","nickname":"new-runner","region":"부산","averagePaceSeconds":390,
                 "preferredDistanceKm":7.25,"currentPassword":"%s","newPassword":"%s"}
                """;
        mvc.perform(put(profilePath).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(passwordChange.formatted(PASSWORD, "1234567")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_SHORT"));
        mvc.perform(put(profilePath).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(passwordChange.formatted(PASSWORD, "가".repeat(24))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_LONG"));
        mvc.perform(put(profilePath).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(passwordChange.formatted("wrong-password", "new-password123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INCORRECT_CURRENT_PASSWORD"));
        mvc.perform(put(profilePath).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(passwordChange.formatted(PASSWORD, "new-password123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newPassword").doesNotExist());
        entityManager.flush();
        entityManager.clear();
        assertThat(passwordEncoder.matches("new-password123", members.findByEmail(EMAIL).orElseThrow().getPassword())).isTrue();
    }

    @Test
    void signupRejectsShortPasswordWithSameErrorAsPasswordChange() throws Exception {
        mvc.perform(post("/api/v1/members/signup").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"signup@auth.test","password":"1234567","name":"러너","nickname":"signup-runner",
                 "region":"서울","averagePaceSeconds":360,"preferredDistanceKm":5}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_SHORT"));
    }

    @Test
    void expiredCookieCannotRestoreLogin() throws Exception {
        Cookie cookie = login();
        entityManager.flush();
        jdbc.update("update auth_refresh_sessions set expires_at = CURRENT_TIMESTAMP - interval '1 second'");
        entityManager.clear();
        mvc.perform(post(AUTH + "/refresh").header(HEADER, "1").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowedPreflightWorksWithoutCredentials() throws Exception {
        mvc.perform(options(AUTH + "/refresh")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HEADER))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString(HEADER)));
    }

    private Cookie login() throws Exception {
        String setCookie = mvc.perform(post(AUTH + "/login").header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("email", EMAIL, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=604800")))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        return new Cookie(COOKIE, setCookie.split(";", 2)[0].substring(COOKIE.length() + 1));
    }
}
