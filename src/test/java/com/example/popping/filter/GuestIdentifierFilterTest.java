package com.example.popping.filter;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.popping.service.GuestIdentifierService;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GuestIdentifierFilterTest {

    private MockMvc mockMvc;
    private GuestIdentifierService guestIdentifierService;

    @BeforeEach
    void setUp() {
        guestIdentifierService = new GuestIdentifierService();
        ReflectionTestUtils.setField(guestIdentifierService, "secret", "test-secret");

        GuestIdentifierFilter filter = new GuestIdentifierFilter(guestIdentifierService);
        ReflectionTestUtils.setField(filter, "secureCookie", false);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new DummyController())
                .addFilter(filter)
                .build();
    }

    @Test
    @DisplayName("쿠키 없음: Set-Cookie로 새 guestIdentifier를 발급한다")
    void noCookie_issuesNewCookie() throws Exception {
        mockMvc.perform(get("/test"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("guestIdentifier=")));
    }

    @Test
    @DisplayName("유효한 쿠키: Set-Cookie 재발급 없음")
    void validCookie_noReissue() throws Exception {
        String validCookie = guestIdentifierService.generate();

        mockMvc.perform(get("/test")
                        .cookie(new Cookie("guestIdentifier", validCookie)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("서명 변조된 쿠키: 새 쿠키를 재발급한다")
    void tamperedCookie_reissues() throws Exception {
        mockMvc.perform(get("/test")
                        .cookie(new Cookie("guestIdentifier", "some-uuid.INVALIDSIG")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("guestIdentifier=")));
    }

    @Test
    @DisplayName("구형 guest-xxxx 형태 쿠키: 새 쿠키를 재발급한다")
    void legacyCookie_reissues() throws Exception {
        mockMvc.perform(get("/test")
                        .cookie(new Cookie("guestIdentifier", "guest-abc123def456")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("guestIdentifier=")));
    }

    @Test
    @DisplayName("발급된 쿠키는 SameSite=Lax 속성을 포함한다")
    void issuedCookie_hasSameSiteLax() throws Exception {
        mockMvc.perform(get("/test"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")));
    }

    @Test
    @DisplayName("발급된 쿠키는 path=/ 속성을 포함한다")
    void issuedCookie_hasRootPath() throws Exception {
        mockMvc.perform(get("/test"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/")));
    }

    @RestController
    static class DummyController {
        @GetMapping("/test")
        String test() {
            return "ok";
        }
    }
}
