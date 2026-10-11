package com.example.payment.config;

import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Where the rate limiter keeps its token buckets (docs/episodes/bonus-12): the
 * rate_limit_bucket table (V12), so every instance takes from the same bucket.
 * A bucket in each instance's memory would let a customer through once per
 * instance.
 */
@Configuration(proxyBeanMethods = false)
class RateLimitConfiguration {

	// SELECT ... FOR UPDATE on the customer's row: two instances taking a token at once wait for each other.
	@Bean
	ProxyManager<String> rateLimitBuckets(DataSource dataSource) {
		return Bucket4jPostgreSQL.selectForUpdateBasedBuilder(dataSource)
				.primaryKeyMapper(PrimaryKeyMapper.STRING)
				.table("rate_limit_bucket")
				.build();
	}
}
