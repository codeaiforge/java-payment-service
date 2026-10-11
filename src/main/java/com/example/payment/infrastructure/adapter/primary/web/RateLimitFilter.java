package com.example.payment.infrastructure.adapter.primary.web;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Rate limits per customer (docs/episodes/bonus-12): technical protection, so
 * one customer's script can't slow the service down for everyone. Not the
 * payment limit of Episode 12, which is a business decision about money.
 *
 * Each customer has a token bucket per limit: every request takes a token,
 * tokens come back at a steady rate, and an empty bucket is a 429 before any
 * work is done. The buckets are rows in PostgreSQL, shared by every instance.
 *
 * A servlet filter, after Spring Security's (it needs the customer ID, the
 * token's sub) and before the controllers. No use case knows about it.
 */
@Component
class RateLimitFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

	/** One limit: at most this many tokens, refilled steadily over the period. */
	record Limit(String name, long tokens, Duration period) {

		BucketConfiguration bucket() {
			return BucketConfiguration.builder()
					.addLimit(limit -> limit.capacity(tokens).refillGreedy(tokens, period))
					.build();
		}

		// The numbers are part of the key: after a limit changes, customers start on a fresh bucket of the new size.
		// Bucket4j would otherwise keep using the size stored in the existing row.
		String key(String customerId) {
			return name + ":" + tokens + "/" + period + ":" + customerId;
		}
	}

	private final ProxyManager<String> buckets;
	private final Limit requests;
	private final Limit payments;
	private final MeterRegistry registry;
	private final Counter unavailable;

	RateLimitFilter(ProxyManager<String> buckets, MeterRegistry registry,
			@Value("${payment.rate-limit.requests-per-second}") long requestsPerSecond,
			@Value("${payment.rate-limit.payments-per-minute}") long paymentsPerMinute) {
		this.buckets = buckets;
		this.registry = registry;
		this.requests = new Limit("requests", requestsPerSecond, Duration.ofSeconds(1));
		this.payments = new Limit("payments", paymentsPerMinute, Duration.ofMinutes(1));
		// Up front, so a rejection or an outage is an increase from 0 that Prometheus can see, not a new series at 1.
		for (Limit limit : List.of(requests, payments)) {
			registry.counter("rate.limit.rejected", "limit", limit.name());
		}
		this.unavailable = registry.counter("rate.limit.unavailable");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Principal caller = request.getUserPrincipal();
		// No customer: a public endpoint (health, metrics, API docs), or a request Spring Security has already refused.
		// SHORTCUT: no per-IP limit; an API gateway or load balancer in front of the service is the place for one.
		if (caller == null) {
			chain.doFilter(request, response);
			return;
		}
		List<Limit> limits = isCreatePayment(request) ? List.of(requests, payments) : List.of(requests);
		List<String> policies = new ArrayList<>();
		List<String> remaining = new ArrayList<>();
		for (Limit limit : limits) {
			ConsumptionProbe probe = takeToken(limit, caller.getName());
			if (probe == null) {
				continue;
			}
			policies.add("\"%s\";q=%d;w=%d".formatted(limit.name(), limit.tokens(), limit.period().toSeconds()));
			remaining.add("\"%s\";r=%d;t=%d".formatted(limit.name(), probe.getRemainingTokens(), secondsUntilAvailable(probe)));
			if (!probe.isConsumed()) {
				rateLimitHeaders(response, policies, remaining);
				tooManyRequests(response, limit, probe);
				return;
			}
		}
		rateLimitHeaders(response, policies, remaining);
		chain.doFilter(request, response);
	}

	// The limiter's store is PostgreSQL. If it doesn't answer, the request goes through: refusing every request would
	// turn a limiter outage into a full outage. The cost: while it lasts, nobody is limited. It is counted and logged.
	private ConsumptionProbe takeToken(Limit limit, String customerId) {
		try {
			return buckets.getProxy(limit.key(customerId), limit::bucket).tryConsumeAndReturnRemaining(1);
		}
		catch (RuntimeException e) {
			unavailable.increment();
			log.warn("Rate limiter unavailable, request let through: {}", e.toString());
			return null;
		}
	}

	private static boolean isCreatePayment(HttpServletRequest request) {
		return "POST".equals(request.getMethod()) && "/payments".equals(request.getRequestURI());
	}

	// The IETF draft's two fields (draft-ietf-httpapi-ratelimit-headers): the policies, and what is left of each.
	private static void rateLimitHeaders(HttpServletResponse response, List<String> policies, List<String> remaining) {
		if (!policies.isEmpty()) {
			response.setHeader("RateLimit-Policy", String.join(", ", policies));
			response.setHeader("RateLimit", String.join(", ", remaining));
		}
	}

	// A problem detail, as every other error (Episode 8). Written here: a filter runs before Spring MVC, so the
	// exception handler never sees it.
	private void tooManyRequests(HttpServletResponse response, Limit limit, ConsumptionProbe probe) throws IOException {
		registry.counter("rate.limit.rejected", "limit", limit.name()).increment();
		Map<String, Object> problem = new LinkedHashMap<>();
		problem.put("type", "about:blank");
		problem.put("title", HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
		problem.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
		long retryAfter = secondsUntilAvailable(probe);
		problem.put("detail", "Rate limit '%s' exceeded; retry in %d seconds".formatted(limit.name(), retryAfter));
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.getWriter().write(JsonMapper.shared().writeValueAsString(problem));
	}

	// When the next token arrives if the bucket is empty, when it is full again otherwise. Whole seconds, rounded up:
	// a client that waits Retry-After seconds finds a token.
	private static long secondsUntilAvailable(ConsumptionProbe probe) {
		long nanos = probe.getRemainingTokens() == 0 ? probe.getNanosToWaitForRefill() : probe.getNanosToWaitForReset();
		return (nanos + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1);
	}
}
