package com.example.popping.relay;

import com.example.popping.dto.LikeResponse;

/**
 * Wire format of a relayed like update.
 *
 * @param origin id of the publishing instance, which already delivered the update locally
 */
public record LikeRelayMessage(String origin, LikeResponse update) {
}
