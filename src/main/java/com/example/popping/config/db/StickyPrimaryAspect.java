package com.example.popping.config.db;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Registers a TransactionSynchronization callback inside a write transaction.
 * When the transaction commits successfully, the afterCommit callback sets the STICKY_PRIMARY cookie
 * so subsequent requests from the same client read from master for a brief window.
 *
 * Order is set to LOWEST_PRECEDENCE to ensure this runs INSIDE the transaction proxy
 * (transaction interceptor has default order = LOWEST_PRECEDENCE, lower order runs first/outside).
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class StickyPrimaryAspect {

	private static final String COOKIE_NAME = "STICKY_PRIMARY";
	private static final int STICKY_DURATION_SECONDS = 3;

	@Value("${guest.identifier.secure-cookie:false}")
	private boolean secureCookie;

	@Before("@within(org.springframework.transaction.annotation.Transactional) "
			+ "&& !@annotation(org.springframework.transaction.annotation.Transactional)")
	public void beforeClassLevelWriteTransaction() {
		if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
			return;
		}
		registerStickyCallback();
	}

	@Before("@annotation(tx)")
	public void beforeMethodLevelWriteTransaction(org.springframework.transaction.annotation.Transactional tx) {
		if (tx.readOnly()) {
			return;
		}
		registerStickyCallback();
	}

	private void registerStickyCallback() {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				setStickyPrimaryCookie();
			}
		});
	}

	private void setStickyPrimaryCookie() {
		ServletRequestAttributes attrs =
				(ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
		if (attrs == null) {
			return;
		}
		HttpServletResponse response = attrs.getResponse();
		if (response == null) {
			return;
		}
		Cookie cookie = new Cookie(COOKIE_NAME, "1");
		cookie.setPath("/");
		cookie.setMaxAge(STICKY_DURATION_SECONDS);
		cookie.setHttpOnly(true);
		cookie.setSecure(secureCookie);
		response.addCookie(cookie);
	}
}
