package com.example.popping.config.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StickyPrimaryHolderTest {

	@AfterEach
	void tearDown() {
		StickyPrimaryHolder.clear();
	}

	@Test
	@DisplayName("default state is not sticky")
	void defaultIsNotSticky() {
		assertThat(StickyPrimaryHolder.isSticky()).isFalse();
	}

	@Test
	@DisplayName("markSticky sets flag to true")
	void markStickySetsFlag() {
		StickyPrimaryHolder.markSticky();
		assertThat(StickyPrimaryHolder.isSticky()).isTrue();
	}

	@Test
	@DisplayName("clear resets flag to false")
	void clearResetsFlag() {
		StickyPrimaryHolder.markSticky();
		StickyPrimaryHolder.clear();
		assertThat(StickyPrimaryHolder.isSticky()).isFalse();
	}

	@Test
	@DisplayName("ThreadLocal is isolated between threads")
	void threadIsolation() throws InterruptedException {
		StickyPrimaryHolder.markSticky();

		boolean[] otherThreadSticky = {true};
		Thread otherThread = new Thread(() -> {
			otherThreadSticky[0] = StickyPrimaryHolder.isSticky();
		});
		otherThread.start();
		otherThread.join();

		assertThat(otherThreadSticky[0]).isFalse();
		assertThat(StickyPrimaryHolder.isSticky()).isTrue();
	}
}
