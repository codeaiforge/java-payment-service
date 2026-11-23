package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.usecase.query.StatusTransition;

import java.time.Instant;

public record StatusTransitionResponse(String from, String to, Instant at) {

	static StatusTransitionResponse from(StatusTransition transition) {
		return new StatusTransitionResponse(transition.from().name(), transition.to().name(), transition.at());
	}
}
