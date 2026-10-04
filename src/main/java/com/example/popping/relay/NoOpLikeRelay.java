package com.example.popping.relay;

import com.example.popping.dto.LikeResponse;

/** Single-instance mode: every viewer is on this instance's broker already. */
public class NoOpLikeRelay implements LikeRelay {

	@Override
	public void publish(LikeResponse update) {
		// Intentionally empty.
	}
}
