package com.example.payment.application.usecase.query;

import java.time.LocalDate;

/**
 * Request for a page of an account's statement (docs/episodes/bonus-09).
 *
 * @param customerId the authenticated caller; only the holder of the account may read its statement
 * @param status     COMPLETED, FAILED or CANCELLED, or null for all three
 * @param from       the first day included, in UTC, or null for no lower bound
 * @param to         the last day included, in UTC, or null for no upper bound
 * @param page       zero-based
 */
public record GetStatementQuery(String accountId, String customerId, String status, LocalDate from, LocalDate to,
		int page, int size) {
}
