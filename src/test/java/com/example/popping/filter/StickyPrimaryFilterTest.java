package com.example.popping.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.example.popping.config.db.StickyPrimaryHolder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class StickyPrimaryFilterTest {

	private final StickyPrimaryFilter filter = new StickyPrimaryFilter();

	@Test
	@DisplayName("sets sticky flag when STICKY_PRIMARY cookie is present")
	void setsSticky() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new Cookie("STICKY_PRIMARY", "1"));
		MockHttpServletResponse response = new MockHttpServletResponse();

		boolean[] stickyDuringFilter = {false};
		FilterChain chain = (req, res) -> {
			stickyDuringFilter[0] = StickyPrimaryHolder.isSticky();
		};

		filter.doFilterInternal(request, response, chain);

		assertThat(stickyDuringFilter[0]).isTrue();
		assertThat(StickyPrimaryHolder.isSticky()).isFalse();
	}

	@Test
	@DisplayName("does not set sticky flag when cookie is absent")
	void doesNotSetSticky() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		boolean[] stickyDuringFilter = {true};
		FilterChain chain = (req, res) -> {
			stickyDuringFilter[0] = StickyPrimaryHolder.isSticky();
		};

		filter.doFilterInternal(request, response, chain);

		assertThat(stickyDuringFilter[0]).isFalse();
	}

	@Test
	@DisplayName("clears ThreadLocal even if chain throws exception")
	void clearsOnException() throws ServletException, IOException {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new Cookie("STICKY_PRIMARY", "1"));
		MockHttpServletResponse response = new MockHttpServletResponse();

		FilterChain chain = mock(FilterChain.class);
		doThrow(new RuntimeException("simulated error")).when(chain).doFilter(request, response);

		try {
			filter.doFilterInternal(request, response, chain);
		} catch (RuntimeException ignored) {
		}

		assertThat(StickyPrimaryHolder.isSticky()).isFalse();
	}
}
