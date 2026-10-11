package com.example.payment.infrastructure.adapter.primary.web;

import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import io.github.bucket4j.distributed.proxy.generic.compare_and_swap.AbstractCompareAndSwapBasedProxyManager;
import io.github.bucket4j.distributed.proxy.generic.compare_and_swap.AsyncCompareAndSwapOperation;
import io.github.bucket4j.distributed.proxy.generic.compare_and_swap.CompareAndSwapOperation;
import io.github.bucket4j.distributed.remote.RemoteBucketState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter on its own: buckets in a map instead of PostgreSQL, and a clock
 * the test moves. 3 requests per second and 2 payments per minute.
 */
class RateLimitFilterTest {

	private final ManualClock clock = new ManualClock();
	private final InMemoryBuckets buckets = new InMemoryBuckets(clock);
	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final RateLimitFilter filter = new RateLimitFilter(buckets, registry, 3, 2);

	@Test
	void underTheLimitPassesWithWhatIsLeft() throws Exception {
		Result first = createPayment("CUST-1001");

		assertThat(first.passed()).isTrue();
		assertThat(first.response().getHeader("RateLimit-Policy")).isEqualTo("\"requests\";q=3;w=1, \"payments\";q=2;w=60");
		assertThat(first.response().getHeader("RateLimit")).isEqualTo("\"requests\";r=2;t=1, \"payments\";r=1;t=30");
	}

	@Test
	void overTheLimitIs429WithRetryAfterAndDoesNotReachTheController() throws Exception {
		createPayment("CUST-1001");
		createPayment("CUST-1001");

		Result third = createPayment("CUST-1001");

		assertThat(third.passed()).isFalse();
		assertThat(third.response().getStatus()).isEqualTo(429);
		// 2 a minute: the next token is 30 seconds away.
		assertThat(third.response().getHeader("Retry-After")).isEqualTo("30");
		assertThat(third.response().getContentType()).isEqualTo("application/problem+json");
		assertThat(third.response().getContentAsString())
				.contains("\"status\":429", "\"detail\":\"Rate limit 'payments' exceeded; retry in 30 seconds\"");
		assertThat(registry.counter("rate.limit.rejected", "limit", "payments").count()).isEqualTo(1);
	}

	@Test
	void aTokenComesBackAfterItsShareOfThePeriod() throws Exception {
		createPayment("CUST-1001");
		createPayment("CUST-1001");

		clock.advance(Duration.ofSeconds(30));

		assertThat(createPayment("CUST-1001").passed()).isTrue();
		assertThat(createPayment("CUST-1001").passed()).isFalse();
	}

	@Test
	void everyRequestCountsTowardsTheRequestLimit() throws Exception {
		for (int i = 0; i < 3; i++) {
			assertThat(request("GET", "/payments/42", "CUST-1001").passed()).isTrue();
		}

		Result fourth = request("GET", "/payments/42", "CUST-1001");

		assertThat(fourth.response().getStatus()).isEqualTo(429);
		assertThat(fourth.response().getHeader("Retry-After")).isEqualTo("1");
		assertThat(registry.counter("rate.limit.rejected", "limit", "requests").count()).isEqualTo(1);
	}

	@Test
	void anotherCustomerIsNotAffected() throws Exception {
		createPayment("CUST-1001");
		createPayment("CUST-1001");
		assertThat(createPayment("CUST-1001").passed()).isFalse();

		assertThat(createPayment("CUST-2002").passed()).isTrue();
	}

	@Test
	void anUnauthenticatedRequestIsNotLimitedHere() throws Exception {
		for (int i = 0; i < 10; i++) {
			assertThat(request("GET", "/actuator/health", null).passed()).isTrue();
		}
		assertThat(buckets.size()).isZero();
	}

	@Test
	void whenTheStoreIsDownRequestsPassAndAreCounted() throws Exception {
		buckets.down = true;

		for (int i = 0; i < 5; i++) {
			Result result = createPayment("CUST-1001");
			assertThat(result.passed()).isTrue();
			assertThat(result.response().getHeader("RateLimit")).isNull();
		}

		assertThat(registry.counter("rate.limit.unavailable").count()).isEqualTo(10);
	}

	private Result createPayment(String customerId) throws Exception {
		return request("POST", "/payments", customerId);
	}

	private Result request(String method, String uri, String customerId) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
		if (customerId != null) {
			request.setUserPrincipal(() -> customerId);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request, response, chain);
		return new Result(chain.getRequest() != null, response);
	}

	private record Result(boolean passed, MockHttpServletResponse response) {
	}

	private static final class ManualClock implements TimeMeter {

		private long nanos;

		void advance(Duration duration) {
			nanos += duration.toNanos();
		}

		@Override
		public long currentTimeNanos() {
			return nanos;
		}

		@Override
		public boolean isWallClockBased() {
			return false;
		}
	}

	// Bucket4j's compare-and-swap contract over a map: what a store must do, without the store.
	private static final class InMemoryBuckets extends AbstractCompareAndSwapBasedProxyManager<String> {

		private final Map<String, byte[]> states = new ConcurrentHashMap<>();
		boolean down;

		InMemoryBuckets(TimeMeter clock) {
			super(ClientSideConfig.getDefault().withClientClock(clock));
		}

		int size() {
			return states.size();
		}

		@Override
		protected CompareAndSwapOperation beginCompareAndSwapOperation(String key) {
			return new CompareAndSwapOperation() {
				@Override
				public Optional<byte[]> getStateData(Optional<Long> timeoutNanos) {
					if (down) {
						throw new IllegalStateException("store down");
					}
					return Optional.ofNullable(states.get(key));
				}

				@Override
				public boolean compareAndSwap(byte[] originalData, byte[] newData, RemoteBucketState newState,
						Optional<Long> timeoutNanos) {
					byte[] current = states.get(key);
					if (!Arrays.equals(current, originalData)) {
						return false;
					}
					states.put(key, newData);
					return true;
				}
			};
		}

		@Override
		protected AsyncCompareAndSwapOperation beginAsyncCompareAndSwapOperation(String key) {
			throw new UnsupportedOperationException();
		}

		@Override
		protected CompletableFuture<Void> removeAsync(String key) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void removeProxy(String key) {
			states.remove(key);
		}

		@Override
		public boolean isAsyncModeSupported() {
			return false;
		}
	}
}
