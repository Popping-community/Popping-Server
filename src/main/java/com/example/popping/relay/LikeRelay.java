package com.example.popping.relay;

import com.example.popping.dto.LikeResponse;

/**
 * Hands a like update to the other application instances, whose viewers subscribe to their
 * own in-memory broker and would otherwise never see it.
 *
 * <p>Called after this instance has already delivered the update to its own viewers. Must not
 * throw into the caller or block it on the network: the like is committed, and a lost relay
 * only leaves other viewers behind until their next refetch.
 */
public interface LikeRelay {

	void publish(LikeResponse update);
}
