package com.example.popping.config.db;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import com.zaxxer.hikari.HikariDataSource;

@Configuration
@ConditionalOnProperty(prefix = "app.datasource.write", name = "jdbc-url")
public class DataSourceConfig {

	@Bean
	@ConfigurationProperties(prefix = "app.datasource.write")
	public DataSource writeDataSource() {
		return DataSourceBuilder.create()
				.type(HikariDataSource.class)
				.build();
	}

	@Bean
	@ConfigurationProperties(prefix = "app.datasource.read")
	public DataSource readDataSource() {
		return DataSourceBuilder.create()
				.type(HikariDataSource.class)
				.build();
	}

	@Bean
	@Primary
	public DataSource dataSource(
			@Qualifier("writeDataSource") DataSource writeDataSource,
			@Qualifier("readDataSource") DataSource readDataSource) {
		LazyConnectionDataSourceProxy proxy = new LazyConnectionDataSourceProxy(writeDataSource);
		proxy.setReadOnlyDataSource(readDataSource);
		return proxy;
	}
}
