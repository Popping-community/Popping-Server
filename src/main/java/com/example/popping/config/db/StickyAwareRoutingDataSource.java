package com.example.popping.config.db;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import java.util.Map;

/**
 * Routes read-only queries to either the replica or master datasource
 * based on the sticky-primary flag.
 *
 * When sticky is active (user just performed a write), reads go to master
 * to ensure read-your-own-writes consistency.
 * Otherwise, reads go to replica for load distribution.
 */
public class StickyAwareRoutingDataSource extends AbstractRoutingDataSource {

	private static final String MASTER = "MASTER";
	private static final String REPLICA = "REPLICA";

	public StickyAwareRoutingDataSource(DataSource masterDataSource, DataSource replicaDataSource) {
		Map<Object, Object> targetDataSources = Map.of(
				MASTER, masterDataSource,
				REPLICA, replicaDataSource
		);
		setTargetDataSources(targetDataSources);
		setDefaultTargetDataSource(replicaDataSource);
		afterPropertiesSet();
	}

	@Override
	protected Object determineCurrentLookupKey() {
		if (StickyPrimaryHolder.isSticky()) {
			return MASTER;
		}
		return REPLICA;
	}
}
