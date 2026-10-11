package com.example.payment.infrastructure.adapter.secondary.webhook;

import com.example.payment.application.port.secondary.WebhookSender;
import com.example.payment.domain.entity.WebhookSubscription;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.infrastructure.adapter.secondary.webhook.PublicAddressResolver.RefusedAddressException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sends webhooks over HTTPS with a plain HTTP client (docs/episodes/bonus-11).
 * Not Feign: a Feign client is one configured system, and here every customer
 * brings their own URL.
 *
 * The endpoint is untrusted, so the client is configured for that:
 * <ul>
 * <li>every host is resolved by PublicAddressResolver, which refuses our own
 * network, and the connection goes to the addresses it checked;</li>
 * <li>redirects are not followed: a 302 to http://169.254.169.254/ would get
 * round the check on the registered URL. A redirect is a failed delivery;</li>
 * <li>short deadlines, and one for the whole exchange, so a slow endpoint holds
 * up the other deliveries by seconds at most;</li>
 * <li>the response body is never read, so never logged: only the status
 * counts. Nor the URL, which can hold a token in its path; failures are logged
 * by webhook ID;</li>
 * <li>no traceparent header, nor any other tracing: our trace IDs are internal,
 * and would tell an outsider how we trace, and let them pick IDs that collide
 * with ours if they ever sent them back. No HTTP client observation either, so
 * no span carries the customer's URL.</li>
 * </ul>
 */
@Component
class HttpWebhookSender implements WebhookSender, DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(HttpWebhookSender.class);
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
	// Each read may take up to RESPONSE_TIMEOUT, so an endpoint sending its headers a byte at a time could take
	// minutes; the exchange is cancelled at this point whatever it is doing.
	private static final Duration DEADLINE = Duration.ofSeconds(15);

	private final PublicAddressResolver resolver;
	private final CloseableHttpClient client;
	private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
			Thread.ofPlatform().name("webhook-deadline").daemon().factory());

	@Autowired
	HttpWebhookSender(PublicAddressResolver resolver, SslBundles sslBundles,
			@Value("${payment.webhooks.ssl-bundle}") String sslBundle) throws NoSuchAlgorithmException {
		this(resolver, sslBundle.isBlank() ? SSLContext.getDefault() : sslBundles.getBundle(sslBundle).createSslContext());
	}

	HttpWebhookSender(PublicAddressResolver resolver, SSLContext sslContext) {
		this.resolver = resolver;
		this.client = HttpClients.custom()
				.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
						.setDnsResolver(resolver)
						.setTlsSocketStrategy(new DefaultClientTlsStrategy(sslContext))
						.setDefaultConnectionConfig(ConnectionConfig.custom()
								.setConnectTimeout(Timeout.of(CONNECT_TIMEOUT))
								.setSocketTimeout(Timeout.of(RESPONSE_TIMEOUT))
								.build())
						.build())
				.setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.of(RESPONSE_TIMEOUT)).build())
				.disableRedirectHandling()
				// Retries are the dispatcher's, with backoff and a record in the database.
				.disableAutomaticRetries()
				.disableCookieManagement()
				.build();
	}

	@Override
	public boolean isPublic(URI url) {
		try {
			resolver.resolve(url.getHost());
			return true;
		}
		catch (UnknownHostException e) {
			return false;
		}
	}

	@Override
	public Attempt send(WebhookSubscription subscription, PaymentEvent event, Instant now) {
		byte[] body = JsonMapper.shared().writeValueAsBytes(WebhookPayload.from(event));
		String id = event.eventId().toString();
		String timestamp = Long.toString(now.getEpochSecond());
		HttpPost post = new HttpPost(subscription.url());
		post.setHeader("Webhook-Id", id);
		post.setHeader("Webhook-Timestamp", timestamp);
		post.setHeader("Webhook-Signature", WebhookSignature.sign(subscription.secret(), id, timestamp, body));
		post.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
		ScheduledFuture<?> deadline = deadlines.schedule(post::cancel, DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
		// Closed without reading the body: the connection is dropped rather than reused, which costs a handshake.
		try (ClassicHttpResponse response = client.executeOpen(null, post, null)) {
			int status = response.getCode();
			return new Attempt(status >= 200 && status < 300, status);
		}
		catch (RefusedAddressException e) {
			log.warn("Webhook {} not sent: its host resolves to an address webhooks may not call", subscription.id());
			return new Attempt(false, 0);
		}
		// The class only: the message can hold the host, and the host the customer's identity.
		catch (IOException e) {
			log.warn("Webhook {} not sent: {}", subscription.id(), e.getClass().getSimpleName());
			return new Attempt(false, 0);
		}
		finally {
			deadline.cancel(false);
		}
	}

	@Override
	public void destroy() throws IOException {
		deadlines.shutdownNow();
		client.close();
	}
}
