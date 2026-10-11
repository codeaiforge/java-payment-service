package com.example.payment.infrastructure.adapter.primary.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The endpoint to register. Whether it is a URL we may call is the application's question, not this record's. */
public record WebhookRequest(@NotBlank @Size(max = 2048) String url) {
}
