package com.example.popping.filter;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import com.example.popping.service.GuestIdentifierService;

@Component
@RequiredArgsConstructor
public class GuestIdentifierFilter extends OncePerRequestFilter {

    public static final String GUEST_UUID_ATTR = "guestUuid";
    private static final String COOKIE_NAME = "guestIdentifier";

    private final GuestIdentifierService guestIdentifierService;

    @Value("${guest.identifier.secure-cookie:false}")
    private boolean secureCookie;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String cookieValue = extractCookieValue(request);
        Optional<String> uuid = guestIdentifierService.extractUuid(cookieValue);

        if (uuid.isEmpty()) {
            cookieValue = guestIdentifierService.generate();
            uuid = guestIdentifierService.extractUuid(cookieValue);
            ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, cookieValue)
                    .secure(secureCookie)
                    .path("/")
                    .maxAge(Duration.ofDays(365))
                    .sameSite("Lax")
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }

        request.setAttribute(GUEST_UUID_ATTR, uuid.get());

        // @CookieValue("guestIdentifier")로 읽는 MVC 컨트롤러에 UUID만 전달
        chain.doFilter(new UuidCookieWrapper(request, cookieValue, uuid.get()), response);
    }

    private String extractCookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * guestIdentifier 쿠키 값을 UUID로 교체해서 @CookieValue 컨트롤러에 전달.
     */
    private static class UuidCookieWrapper extends HttpServletRequestWrapper {

        private final Cookie[] cookies;

        UuidCookieWrapper(HttpServletRequest request, String originalValue, String uuid) {
            super(request);
            Cookie[] original = request.getCookies();
            if (original == null) {
                Cookie c = new Cookie(COOKIE_NAME, uuid);
                cookies = new Cookie[]{ c };
                return;
            }
            cookies = Arrays.stream(original)
                    .map(c -> {
                        if (!COOKIE_NAME.equals(c.getName())) return c;
                        Cookie replaced = new Cookie(c.getName(), uuid);
                        replaced.setPath(c.getPath());
                        return replaced;
                    })
                    .toArray(Cookie[]::new);
        }

        @Override
        public Cookie[] getCookies() {
            return cookies;
        }
    }
}
