package com.example.popping.relay;

import java.io.IOException;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Delivers like updates published by the other instances to this instance's viewers.
 *
 * <p>Only re-sends to the local broker, never publishes again, so an update cannot loop
 * between instances. Outcomes are counted under {@code like.relay.receive} with a
 * {@code result} tag of {@code delivered}, {@code own}, {@code invalid} or {@code failed}, and
 * {@code dropped} when the listener executor's queue was full (see {@link LikeRelayConfig}).
 */
@Slf4j
public class LikeRelaySubscriber implements MessageListener {

	static final String METRIC = "like.relay.receive";

	private final SimpMessagingTemplate messagingTemplate;
	private final ObjectMapper objectMapper;
	private final String origin;
	private final String destination;
	private final MeterRegistry meterRegistry;

	public LikeRelaySubscriber(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper, String origin,
			String destination, MeterRegistry meterRegistry) {
		this.messagingTemplate = messagingTemplate;
		this.objectMapper = objectMapper;
		this.origin = origin;
		this.destination = destination;
		this.meterRegistry = meterRegistry;
	}

	@Override
	public void onMessage(Message message, byte[] pattern) {
		LikeRelayMessage relayed;
		try {
			relayed = objectMapper.readValue(message.getBody(), LikeRelayMessage.class);
		} catch (IOException e) {
			log.warn("Ignoring unreadable like relay message", e);
			count("invalid");
			return;
		}
		// A body of the JSON literal null deserializes to null rather than failing.
		if (relayed == null || relayed.update() == null || relayed.update().targetId() == null) {
			count("invalid");
			return;
		}
		if (origin.equals(relayed.origin())) {
			// This instance delivered it to its own viewers before publishing.
			count("own");
			return;
		}
		try {
			messagingTemplate.convertAndSend(destination, relayed.update());
			count("delivered");
		} catch (RuntimeException e) {
			log.warn("Relayed like update not delivered target={}:{}",
					relayed.update().targetType(), relayed.update().targetId(), e);
			count("failed");
		}
	}

	private void count(String result) {
		meterRegistry.counter(METRIC, "result", result).increment();
	}
}
