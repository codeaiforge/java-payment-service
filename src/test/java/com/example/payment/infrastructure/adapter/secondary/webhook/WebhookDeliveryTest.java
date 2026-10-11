package com.example.payment.infrastructure.adapter.secondary.webhook;

import com.example.payment.application.service.command.DispatchWebhooksService;
import com.example.payment.application.service.command.InMemoryWebhookRepository;
import com.example.payment.application.service.command.InMemoryWebhookRepository.Row;
import com.example.payment.domain.entity.WebhookSubscription;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.event.PaymentEventType;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.WebhookStatus;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.transaction.support.TransactionOperations;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delivery end to end without Spring (docs/episodes/bonus-11): the dispatcher,
 * the real HTTP sender, and WireMock as the merchant, over HTTPS with a test
 * certificate. The repository is in memory and the clock is the test's, so a
 * day of retries takes milliseconds.
 *
 * WireMock listens on 127.0.0.1, which the sender refuses, so these senders
 * let that one address through, as the end-to-end test's configuration does.
 */
class WebhookDeliveryTest {

	private static final String SECRET = "whsec_test-secret";

	@RegisterExtension
	static WireMockExtension merchant = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort().dynamicHttpsPort()
					.keystorePath("src/test/resources/webhooks/merchant.p12").keystoreType("PKCS12")
					.keystorePassword("merchant-test").keyManagerPassword("merchant-test"))
			.build();

	private final MovableClock clock = new MovableClock(Instant.parse("2026-10-11T10:00:00Z"));
	private final InMemoryWebhookRepository repository = new InMemoryWebhookRepository();
	private final HttpWebhookSender sender = senderAllowing(List.of("127.0.0.1"));
	private final DispatchWebhooksService dispatcher = new DispatchWebhooksService(repository, sender,
			TransactionOperations.withoutTransaction(), clock);
	private WebhookSubscription subscription;

	@BeforeEach
	void subscribe() {
		subscription = WebhookSubscription.register("CUST-1001",
				URI.create("https://127.0.0.1:" + merchant.getHttpsPort() + "/hooks"), SECRET);
		repository.save(subscription);
	}

	@Test
	void aSignedPostArrivesWithTheHeaders() {
		merchant.stubFor(post("/hooks").willReturn(aResponse().withStatus(204)));
		PaymentEvent event = queue();

		dispatcher.dispatchDue();

		LoggedRequest request = merchant.findAll(postRequestedFor(urlEqualTo("/hooks"))).getFirst();
		assertThat(request.getHeader("Webhook-Id")).isEqualTo(event.eventId().toString());
		assertThat(request.getHeader("Webhook-Timestamp")).isEqualTo(Long.toString(clock.instant().getEpochSecond()));
		// What a receiver does: sign what arrived with their copy of the secret, and compare.
		assertThat(request.getHeader("Webhook-Signature")).isEqualTo(WebhookSignature.sign(SECRET,
				request.getHeader("Webhook-Id"), request.getHeader("Webhook-Timestamp"), request.getBody()));
		assertThat(request.getHeader("Content-Type")).startsWith("application/json");
		merchant.verify(postRequestedFor(urlEqualTo("/hooks")).withHeader("traceparent", absent()));

		String body = request.getBodyAsString();
		assertThat((String) JsonPath.read(body, "$.id")).isEqualTo(event.eventId().toString());
		assertThat((String) JsonPath.read(body, "$.type")).isEqualTo("payment.completed");
		assertThat((String) JsonPath.read(body, "$.occurredAt")).isEqualTo("2026-10-11T09:59:00Z");
		assertThat((String) JsonPath.read(body, "$.data.paymentId")).isEqualTo(event.paymentId().toString());
		assertThat((String) JsonPath.read(body, "$.data.amount")).isEqualTo("250.00");
		assertThat((String) JsonPath.read(body, "$.data.currency")).isEqualTo("EUR");
		assertThat((String) JsonPath.read(body, "$.data.reference")).isEqualTo("Invoice 12345");
		assertThat(onlyDelivery().status).isEqualTo("DELIVERED");
	}

	@Test
	void aFailedDeliveryIsDeliveredOnRetry() {
		merchant.stubFor(post("/hooks").inScenario("flaky").whenScenarioStateIs(STARTED)
				.willReturn(aResponse().withStatus(500)).willSetStateTo("recovered"));
		merchant.stubFor(post("/hooks").inScenario("flaky").whenScenarioStateIs("recovered")
				.willReturn(aResponse().withStatus(200)));
		queue();

		dispatcher.dispatchDue();
		assertThat(onlyDelivery().status).isEqualTo("PENDING");
		assertThat(onlyDelivery().lastResponseStatus).isEqualTo(500);
		dispatcher.dispatchDue();
		merchant.verify(1, postRequestedFor(urlEqualTo("/hooks")));

		clock.move(DispatchWebhooksService.FIRST_RETRY);
		dispatcher.dispatchDue();

		merchant.verify(2, postRequestedFor(urlEqualTo("/hooks")));
		assertThat(onlyDelivery().status).isEqualTo("DELIVERED");
		assertThat(onlyDelivery().attempts).isEqualTo(2);
	}

	@Test
	void anEndpointThatAlwaysFailsIsGivenUpAfterTheMaximumAge() {
		merchant.stubFor(post("/hooks").willReturn(aResponse().withStatus(500)));
		queue();
		Instant queuedAt = clock.instant();

		while (onlyDelivery().status.equals("PENDING")) {
			assertThat(clock.instant()).isBefore(queuedAt.plus(DispatchWebhooksService.MAX_AGE).plus(DispatchWebhooksService.LONGEST_WAIT));
			dispatcher.dispatchDue();
			clock.move(Duration.ofMinutes(1));
		}

		assertThat(onlyDelivery().status).isEqualTo("FAILED");
		assertThat(clock.instant()).isAfter(queuedAt.plus(DispatchWebhooksService.MAX_AGE));
		// 7 attempts in the first hour (30s, 1m, 2m ... 32m), then one every 30 to 60 minutes (jitter) until a day is up.
		assertThat(onlyDelivery().attempts).isBetween(30, 54);
		assertThat(subscription.consecutiveFailures()).isEqualTo(1);
		assertThat(subscription.isActive()).isTrue();
	}

	@Test
	void fiveDeliveriesGivenUpInARowDisableTheSubscription() {
		merchant.stubFor(post("/hooks").willReturn(aResponse().withStatus(503)));
		for (int i = 0; i < WebhookSubscription.MAX_CONSECUTIVE_FAILURES; i++) {
			queue();
		}

		dispatcher.dispatchDue();
		clock.move(DispatchWebhooksService.MAX_AGE);
		dispatcher.dispatchDue();

		assertThat(repository.deliveries()).allSatisfy(row -> assertThat(row.status).isEqualTo("FAILED"));
		assertThat(subscription.status()).isEqualTo(WebhookStatus.DISABLED);

		int sent = merchant.findAll(postRequestedFor(urlEqualTo("/hooks"))).size();
		queue();
		dispatcher.dispatchDue();
		assertThat(merchant.findAll(postRequestedFor(urlEqualTo("/hooks")))).hasSize(sent);
	}

	// A redirect could point anywhere, inside our network too, and nobody checked where.
	@Test
	void aRedirectIsNotFollowed() {
		merchant.stubFor(post("/hooks").willReturn(aResponse().withStatus(307).withHeader("Location", "/elsewhere")));
		merchant.stubFor(post("/elsewhere").willReturn(aResponse().withStatus(200)));
		queue();

		dispatcher.dispatchDue();

		merchant.verify(0, postRequestedFor(urlEqualTo("/elsewhere")));
		assertThat(onlyDelivery().status).isEqualTo("PENDING");
		assertThat(onlyDelivery().lastResponseStatus).isEqualTo(307);
	}

	// DNS rebinding: the host resolved to a public address when it was registered, and resolves to 127.0.0.1 now.
	@Test
	void aHostThatNowResolvesInsideOurNetworkIsNotCalled() {
		merchant.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)));
		AtomicInteger lookups = new AtomicInteger();
		HttpWebhookSender rebound = new HttpWebhookSender(new PublicAddressResolver(host -> new InetAddress[] {
				InetAddress.ofLiteral(lookups.getAndIncrement() == 0 ? "93.184.215.14" : "127.0.0.1")}, List.of()),
				trustingTheMerchant());
		URI url = URI.create("https://merchant.example.com:" + merchant.getHttpsPort() + "/hooks");
		WebhookSubscription registered = WebhookSubscription.register("CUST-1001", url, SECRET);

		assertThat(rebound.isPublic(url)).isTrue();
		assertThat(rebound.send(registered, event(), clock.instant()).delivered()).isFalse();
		merchant.verify(0, anyRequestedFor(anyUrl()));
	}

	// An address in the URL is checked like a name: the client doesn't skip the resolver for it.
	@Test
	void anAddressInTheUrlIsCheckedToo() {
		merchant.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)));

		assertThat(senderAllowing(List.of()).send(subscription, event(), clock.instant()).delivered()).isFalse();
		merchant.verify(0, anyRequestedFor(anyUrl()));
	}

	private PaymentEvent queue() {
		PaymentEvent event = event();
		repository.enqueue(subscription.id(), event, clock.instant());
		return event;
	}

	private PaymentEvent event() {
		return new PaymentEvent(UUID.randomUUID(), PaymentEventType.PAYMENT_COMPLETED, PaymentId.newId(), null,
				new AccountId("ACC-10001"), new AccountId("ACC-20001"), new Money(new BigDecimal("250.00"), Currency.EUR),
				new PaymentReference("Invoice 12345"), clock.instant().minusSeconds(60));
	}

	private Row onlyDelivery() {
		assertThat(repository.deliveries()).hasSize(1);
		return repository.deliveries().getFirst();
	}

	private static HttpWebhookSender senderAllowing(List<String> addresses) {
		return new HttpWebhookSender(new PublicAddressResolver(InetAddress::getAllByName, addresses), trustingTheMerchant());
	}

	// What spring.ssl.bundle.pem.*.truststore does in the end-to-end test: trust the merchant's test certificate.
	private static SSLContext trustingTheMerchant() {
		try (InputStream certificate = Files.newInputStream(Path.of("src/test/resources/webhooks/merchant.crt"))) {
			KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
			trusted.load(null, null);
			trusted.setCertificateEntry("merchant", CertificateFactory.getInstance("X.509").generateCertificate(certificate));
			TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			trust.init(trusted);
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, trust.getTrustManagers(), null);
			return context;
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static final class MovableClock extends Clock {

		private Instant now;

		MovableClock(Instant now) {
			this.now = now;
		}

		void move(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException();
		}
	}
}
