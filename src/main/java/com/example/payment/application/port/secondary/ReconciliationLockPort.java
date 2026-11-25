package com.example.payment.application.port.secondary;

import com.example.payment.application.usecase.query.ReconciliationStatus;

import java.util.Optional;

/** Reads the lock reconciliation runs under; empty if it has never been taken. */
public interface ReconciliationLockPort {

	Optional<ReconciliationStatus> find();
}
