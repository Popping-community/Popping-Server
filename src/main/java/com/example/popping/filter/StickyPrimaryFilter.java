package com.example.popping.filter;

import java.io.IOException;
import java.util.Arrays;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.popping.config.db.StickyPrimaryHolder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads the sticky-primary cookie at request start and sets the ThreadLocal flag.
 * Clears the ThreadLocal in finally to prevent leakage to the next request on the same thread.
 */
@Component
public class StickyPrimaryFilter extends OncePerRequestFilter {

	static final String COOKIE_NAME = "STICKY_PRIMARY";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		try {
			if (hasStickyPrimaryCookie(request)) {
				StickyPrimaryHolder.markSticky();
			}
			filterChain.doFilter(request, response);
		} finally {
			StickyPrimaryHolder.clear();
		}
	}

	private boolean hasStickyPrimaryCookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return false;
		}
		return Arrays.stream(cookies)
				.anyMatch(c -> COOKIE_NAME.equals(c.getName()) && "1".equals(c.getValue()));
	}
}
