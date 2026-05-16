package com.example.popping.config.db;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StickyAwareRoutingDataSourceTest {

	@Mock
	private DataSource masterDataSource;

	@Mock
	private DataSource replicaDataSource;

	@Mock
	private Connection masterConnection;

	@Mock
	private Connection replicaConnection;

	private StickyAwareRoutingDataSource routingDataSource;

	@BeforeEach
	void setUp() {
		routingDataSource = new StickyAwareRoutingDataSource(masterDataSource, replicaDataSource);
	}

	@AfterEach
	void tearDown() {
		StickyPrimaryHolder.clear();
	}

	@Test
	@DisplayName("routes to replica when not sticky")
	void routesToReplicaByDefault() throws SQLException {
		when(replicaDataSource.getConnection()).thenReturn(replicaConnection);
		routingDataSource.getConnection();
		verify(replicaDataSource).getConnection();
	}

	@Test
	@DisplayName("routes to master when sticky flag is set")
	void routesToMasterWhenSticky() throws SQLException {
		when(masterDataSource.getConnection()).thenReturn(masterConnection);
		StickyPrimaryHolder.markSticky();
		routingDataSource.getConnection();
		verify(masterDataSource).getConnection();
	}
}
