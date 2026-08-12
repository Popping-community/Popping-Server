package com.example.popping.repository;

/**
 * Current like/dislike counters of a single target, read back after a delta is applied
 * so that broadcasts can carry absolute state instead of an increment.
 */
public interface LikeCountView {
	int getLikeCount();

	int getDislikeCount();
}
