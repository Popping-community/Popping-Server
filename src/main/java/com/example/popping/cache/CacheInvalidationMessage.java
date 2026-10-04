package com.example.popping.cache;

/**
 * Wire format of a cross-instance cache eviction.
 *
 * @param origin id of the instance that published it, so that instance can skip its own message
 */
public record CacheInvalidationMessage(String cacheName, Long key, String origin) { }
