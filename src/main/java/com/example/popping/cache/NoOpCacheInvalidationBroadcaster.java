package com.example.popping.cache;

/** Single-instance mode: there is no other JVM to tell. */
public class NoOpCacheInvalidationBroadcaster implements CacheInvalidationBroadcaster {

	@Override
	public void broadcast(String cacheName, Long key) {
		// Intentionally empty.
	}
}
