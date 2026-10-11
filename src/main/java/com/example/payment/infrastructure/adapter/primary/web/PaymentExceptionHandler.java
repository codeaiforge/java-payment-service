package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.exception.AccessDeniedException;
import com.example.payment.application.exception.AccountNotFoundException;
import com.example.payment.application.exception.AccountUnavailableException;
import com.example.payment.application.exception.ExternalSystemUnavailableException;
import com.example.payment.application.exception.IdempotencyKeyInProgressException;
import com.example.payment.application.exception.IdempotencyKeyMismatchException;
import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentAuthorizationException;
import com.example.payment.application.exception.PaymentLimitExceededException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.exception.RefundExceedsPaymentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The only place that knows which application exception is which HTTP status.
 * The application says what went wrong; HTTP is how this adapter reports it.
 */
@RestControllerAdvice
class PaymentExceptionHandler {

	// An unknown account is wrong input to a payment, not a missing /payments resource: 400, not 404.
	@ExceptionHandler({PaymentValidationException.class, AccountNotFoundException.class})
	ProblemDetail badRequest(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	// Only for an account the caller doesn't hold: paying from it, or reading its statement. Another customer's payment
	// is a 404 (PaymentNotFoundException), so the API doesn't reveal which payment IDs exist.
	@ExceptionHandler(AccessDeniedException.class)
	ProblemDetail forbidden(AccessDeniedException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
	}

	@ExceptionHandler(PaymentNotFoundException.class)
	ProblemDetail notFound(PaymentNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	// The request is fine and the payment exists; it is just in the wrong state for this action.
	// Same for a key whose first request hasn't finished: retry later.
	@ExceptionHandler({InvalidPaymentStateException.class, IdempotencyKeyInProgressException.class})
	ProblemDetail conflict(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	// Well-formed, but the key belongs to another request; retrying the same thing can never succeed.
	// Same for a payment the authorization or limit system refused: valid, just not allowed.
	// (Not 403: that would say the caller lacks permission, not that the payment was declined. 403 is access
	// control, AccessDeniedException.) Same for a refund larger than what is left to refund.
	@ExceptionHandler({IdempotencyKeyMismatchException.class, PaymentAuthorizationException.class,
			PaymentLimitExceededException.class, RefundExceedsPaymentException.class})
	ProblemDetail unprocessable(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
	}

	// The cause (a Feign exception) is not shown: the caller only needs to know to retry later.
	@ExceptionHandler({AccountUnavailableException.class, ExternalSystemUnavailableException.class})
	ProblemDetail serviceUnavailable(RuntimeException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
	}
}
