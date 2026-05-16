package com.example.popping.config.db;

/**
 * ThreadLocal holder for sticky-primary flag.
 * When set to true, read-only queries are routed to the master (write) datasource
 * to guarantee read-your-own-writes consistency.
 */
public final class StickyPrimaryHolder {

	private static final ThreadLocal<Boolean> STICKY = ThreadLocal.withInitial(() -> Boolean.FALSE);

	private StickyPrimaryHolder() {
	}

	public static boolean isSticky() {
		return STICKY.get();
	}

	public static void markSticky() {
		STICKY.set(Boolean.TRUE);
	}

	public static void clear() {
		STICKY.remove();
	}
}
