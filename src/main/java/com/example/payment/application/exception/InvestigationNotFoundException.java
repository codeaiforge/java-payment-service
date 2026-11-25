package com.example.payment.application.exception;

public class InvestigationNotFoundException extends RuntimeException {

	public InvestigationNotFoundException(String investigationId) {
		super("Investigation " + investigationId + " does not exist");
	}
}
