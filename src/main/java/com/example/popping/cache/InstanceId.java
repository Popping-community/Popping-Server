package com.example.popping.cache;

import java.util.UUID;

/**
 * Identifies this JVM among the application instances, for the lifetime of the process.
 *
 * <p>A restarted instance gets a new id; that is fine because the id is only used to recognize
 * messages this same process published.
 */
public record InstanceId(String value) {

	public static InstanceId random() {
		return new InstanceId(UUID.randomUUID().toString());
	}
}
