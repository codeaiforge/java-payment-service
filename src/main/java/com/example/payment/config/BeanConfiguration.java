package com.example.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Beans that belong to no adapter. */
@Configuration(proxyBeanMethods = false)
class BeanConfiguration {

	// The webhook services read the time from here, so a test can move it forward a day (docs/episodes/bonus-11).
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
