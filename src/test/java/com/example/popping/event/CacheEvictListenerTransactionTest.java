package com.example.popping.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.cache.CacheInvalidationBroadcaster;

import static org.mockito.Mockito.*;

/**
 * Drives the listener through Spring's real transactional event machinery, which calling
 * {@code onCacheEvict} directly would skip: the AFTER_COMMIT callback only runs if the
 * listener is registered by {@code TransactionalEventListenerFactory} and the event is
 * published inside a synchronized transaction.
 */
class CacheEvictListenerTransactionTest {

	private static final CacheInvalidationBroadcaster BROADCASTER = mock(CacheInvalidationBroadcaster.class);

	private AnnotationConfigApplicationContext context;
	private TransactionTemplate tx;

	@BeforeEach
	void setUp() {
		reset(BROADCASTER);
		context = new AnnotationConfigApplicationContext(TestConfig.class);
		tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
	}

	@AfterEach
	void tearDown() {
		context.close();
	}

	@Test
	@DisplayName("커밋되면 전파한다")
	void commit_broadcasts() {
		tx.executeWithoutResult(status -> {
			context.publishEvent(new CacheEvictEvent("boardFirstPage", 3L));
			verifyNoInteractions(BROADCASTER);
		});

		verify(BROADCASTER).broadcast("boardFirstPage", 3L);
	}

	@Test
	@DisplayName("롤백되면 전파하지 않는다 (AC5)")
	void rollback_doesNotBroadcast() {
		tx.executeWithoutResult(status -> {
			context.publishEvent(new CacheEvictEvent("boardFirstPage", 3L));
			status.setRollbackOnly();
		});

		verifyNoInteractions(BROADCASTER);
	}

	@Test
	@DisplayName("트랜잭션 없이 발행하면 fallbackExecution으로 즉시 전파한다")
	void noTransaction_broadcastsImmediately() {
		context.publishEvent(new CacheEvictEvent("boardFirstPage", 3L));

		verify(BROADCASTER).broadcast("boardFirstPage", 3L);
	}

	@Configuration
	@EnableTransactionManagement
	static class TestConfig {

		@Bean
		PlatformTransactionManager transactionManager() {
			return new NoResourceTransactionManager();
		}

		@Bean
		CacheManager cacheManager() {
			return new ConcurrentMapCacheManager("boardFirstPage");
		}

		@Bean
		CacheEvictListener cacheEvictListener(CacheManager cacheManager) {
			return new CacheEvictListener(cacheManager, BROADCASTER);
		}
	}

	/** A transaction manager with no resource, so commit and rollback only run synchronization. */
	static class NoResourceTransactionManager extends AbstractPlatformTransactionManager {

		@Override
		protected Object doGetTransaction() {
			return new Object();
		}

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) {
			// No resource to bind.
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) {
			// Nothing to commit.
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) {
			// Nothing to roll back.
		}
	}
}
