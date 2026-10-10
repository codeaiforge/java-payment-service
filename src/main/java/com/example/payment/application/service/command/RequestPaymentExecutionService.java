package com.example.payment.application.service.command;

import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.RequestPaymentExecutionUseCase;
import com.example.payment.application.port.secondary.PaymentProcessingPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.ExecutePaymentCommand;
import com.example.payment.application.validation.AccountStateValidator;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The request half of execution: check the payment can still be executed and
 * queue it. Authorization, the limit check and the payment system run later,
 * in ExecutePaymentService, when the message is consumed.
 *
 * Nothing is written here, so there is no database change that a failed
 * publish could leave behind: either the message is queued or the caller gets
 * a 503 and the payment is exactly as it was.
 *
 * Access is checked here, while there is a caller. The message carries only
 * the payment ID, and the consumer doesn't check again: it has no caller to
 * check, and this service, behind the check, is the only publisher to the topic.
 */
@Service
public class RequestPaymentExecutionService implements RequestPaymentExecutionUseCase {

	private static final Logger log = LoggerFactory.getLogger(RequestPaymentExecutionService.class);

	private final PaymentRepository paymentRepository;
	private final AccountStateValidator accountStateValidator;
	private final PaymentProcessingPort paymentProcessingPort;

	public RequestPaymentExecutionService(PaymentRepository paymentRepository, AccountStateValidator accountStateValidator,
			PaymentProcessingPort paymentProcessingPort) {
		this.paymentRepository = paymentRepository;
		this.accountStateValidator = accountStateValidator;
		this.paymentProcessingPort = paymentProcessingPort;
	}

	@Override
	public Payment requestExecution(ExecutePaymentCommand command) {
		PaymentId paymentId;
		try {
			paymentId = PaymentId.of(command.paymentId());
		}
		catch (IllegalArgumentException e) {
			throw new PaymentValidationException(e.getMessage(), e);
		}
		Payment payment = paymentRepository.findById(paymentId)
				// Another customer's payment is not found, not forbidden: a 403 would confirm that the ID exists.
				.filter(p -> accountStateValidator.isHeldBy(p.sourceAccountId(), command.customerId()))
				.orElseThrow(() -> new PaymentNotFoundException(paymentId));

		// A quick 409 for the caller, not the guard: the status can change before the message is consumed, and
		// there the domain decides again. Two requests that both pass this check queue two messages; the second
		// is refused when consumed (PaymentProcessingConsumer).
		if (payment.status() != PaymentStatus.CREATED && payment.status() != PaymentStatus.AUTHORIZED) {
			throw new InvalidPaymentStateException(
					"Payment " + paymentId + " is " + payment.status() + " and cannot become " + PaymentStatus.PROCESSING);
		}
		paymentProcessingPort.requestProcessing(paymentId);
		log.info("Payment {} queued for processing", paymentId);
		return payment;
	}
}
