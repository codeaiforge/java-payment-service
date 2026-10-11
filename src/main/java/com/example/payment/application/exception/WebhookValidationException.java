package com.example.payment.application.exception;

/** The endpoint can't be registered: a bad URL, or one we may not call. The message is safe to show the caller. */
public class WebhookValidationException extends RuntimeException {

	public WebhookValidationException(String message) {
		super(message);
	}

	public WebhookValidationException(String message, Throwable cause) {
		super(message, cause);
	}
}
