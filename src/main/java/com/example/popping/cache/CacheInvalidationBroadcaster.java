package com.example.popping.cache;

/**
 * Tells the other application instances that a cache entry is stale.
 *
 * <p>Implementations must never throw into the caller and must not block it on the network:
 * the caller is a write request that has already committed, and a failed broadcast only means
 * the other instances keep the stale entry until its TTL expires.
 */
public interface CacheInvalidationBroadcaster {

	void broadcast(String cacheName, Long key);
}
