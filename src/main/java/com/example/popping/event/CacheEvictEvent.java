package com.example.popping.event;

/**
 * Asks every cache holding {@code key} under {@code cacheName} to drop it after commit.
 *
 * <p>The key is a {@code Long} on purpose: every cache this app evicts is keyed by an entity
 * id, and the event may cross a JVM boundary as JSON. With an {@code Object} key a {@code 1L}
 * sent as JSON comes back as an {@code Integer}, which is not equal to the {@code Long} the
 * cache was filled with, so the remote eviction would silently miss.
 */
public record CacheEvictEvent(String cacheName, Long key) { }
