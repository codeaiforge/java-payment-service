package com.example.payment.application.exception;

/** The investigation exists, but was already decided: approved, rejected or finished. */
public class InvalidInvestigationStateException extends RuntimeException {

	public InvalidInvestigationStateException(String message) {
		super(message);
	}
}
