package com.example.payment.application.service.command;

import com.example.payment.application.port.primary.DeliverWebhookUseCase;
import com.example.payment.application.port.secondary.AccountEnquiryPort;
import com.example.payment.application.port.secondary.AccountEnquiryPort.Account;
import com.example.payment.application.port.secondary.WebhookRepository;
import com.example.payment.domain.entity.WebhookSubscription;
import com.example.payment.domain.event.PaymentEvent;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Turns a payment event into deliveries (docs/episodes/bonus-11): one per
 * active endpoint of the customer who holds the payment's source account, the
 * one who made the payment. Nobody else's endpoint hears of it.
 *
 * Nothing is sent from here. Each delivery is stored, and the dispatcher sends
 * it: an endpoint that is down for an hour must not hold up the topic, and a
 * restart must not lose the retries.
 *
 * Kafka delivers at least once, so the same event can come twice; it is queued
 * once per endpoint all the same.
 */
@Service
public class DeliverWebhookService implements DeliverWebhookUseCase {

	private final AccountEnquiryPort accountEnquiryPort;
	private final WebhookRepository webhookRepository;
	private final Clock clock;

	public DeliverWebhookService(AccountEnquiryPort accountEnquiryPort, WebhookRepository webhookRepository, Clock clock) {
		this.accountEnquiryPort = accountEnquiryPort;
		this.webhookRepository = webhookRepository;
		this.clock = clock;
	}

	// The account system being down throws, and the event is retried through the retry topics like any other.
	@Override
	public void deliver(PaymentEvent event) {
		String customerId = accountEnquiryPort.findAccount(event.sourceAccountId()).map(Account::holderId).orElse(null);
		if (customerId == null) {
			return;
		}
		for (WebhookSubscription subscription : webhookRepository.findSubscriptions(customerId)) {
			if (subscription.isActive()) {
				webhookRepository.enqueue(subscription.id(), event, clock.instant());
			}
		}
	}
}
