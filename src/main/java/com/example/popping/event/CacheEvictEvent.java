package com.example.popping.event;

public record CacheEvictEvent(String cacheName, Object key) {}
