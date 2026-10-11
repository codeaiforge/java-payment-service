package com.example.payment.application.validation;

import com.example.payment.application.exception.AccessDeniedException;
import com.example.payment.application.exception.AccountNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.secondary.AccountEnquiryPort.Account;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AccountStateValidatorTest {

	private static final String CUSTOMER = "CUST-1";

	private static final AccountId ACTIVE = new AccountId("ACC-1");
	private static final AccountId ALSO_ACTIVE = new AccountId("ACC-2");
	private static final AccountId BLOCKED = new AccountId("ACC-3");
	private static final AccountId SOMEONE_ELSES = new AccountId("ACC-4");
	private static final AccountId SOMEONE_ELSES_BLOCKED = new AccountId("ACC-5");
	private static final AccountId NO_HOLDER = new AccountId("ACC-6");
	private static final AccountId UNKNOWN = new AccountId("ACC-9");

	// The port is one method, so a map stands in for the account system.
	private final AccountStateValidator validator = new AccountStateValidator(accountId -> Optional.ofNullable(Map.of(
			ACTIVE, new Account(AccountStatus.ACTIVE, CUSTOMER),
			ALSO_ACTIVE, new Account(AccountStatus.ACTIVE, CUSTOMER),
			BLOCKED, new Account(AccountStatus.BLOCKED, CUSTOMER),
			SOMEONE_ELSES, new Account(AccountStatus.ACTIVE, "CUST-2"),
			SOMEONE_ELSES_BLOCKED, new Account(AccountStatus.BLOCKED, "CUST-2"),
			NO_HOLDER, new Account(AccountStatus.ACTIVE, null)).get(accountId)));

	@Test
	void acceptsTwoActiveAccountsFromOneTheCallerHolds() {
		assertThatCode(() -> validator.validate(ACTIVE, ALSO_ACTIVE, CUSTOMER)).doesNotThrowAnyException();
	}

	// Paying *to* another customer's account is what payments are for: only the source must be the caller's.
	@Test
	void acceptsADestinationSomeoneElseHolds() {
		assertThatCode(() -> validator.validate(ACTIVE, SOMEONE_ELSES, CUSTOMER)).doesNotThrowAnyException();
	}

	@Test
	void rejectsASourceTheCallerDoesNotHold() {
		assertThatExceptionOfType(AccessDeniedException.class)
				.isThrownBy(() -> validator.validate(SOMEONE_ELSES, ACTIVE, CUSTOMER))
				.withMessage("Account ACC-4 is not held by the caller");
	}

	// Checked before the state, so the caller can't learn that someone else's account is blocked.
	@Test
	void ownershipIsCheckedBeforeState() {
		assertThatExceptionOfType(AccessDeniedException.class)
				.isThrownBy(() -> validator.validate(SOMEONE_ELSES_BLOCKED, ACTIVE, CUSTOMER));
	}

	@Test
	void rejectsAnInactiveSource() {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> validator.validate(BLOCKED, ACTIVE, CUSTOMER))
				.withMessage("Source account ACC-3 is not active");
	}

	@Test
	void rejectsAnInactiveDestination() {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> validator.validate(ACTIVE, BLOCKED, CUSTOMER))
				.withMessage("Destination account ACC-3 is not active");
	}

	@Test
	void rejectsAnAccountTheAccountSystemDoesNotKnow() {
		assertThatExceptionOfType(AccountNotFoundException.class)
				.isThrownBy(() -> validator.validate(UNKNOWN, ACTIVE, CUSTOMER))
				.withMessage("Source account ACC-9 does not exist");
	}

	@Test
	void knowsWhoHoldsAnAccount() {
		assertThat(validator.isHeldBy(ACTIVE, CUSTOMER)).isTrue();
		assertThat(validator.isHeldBy(SOMEONE_ELSES, CUSTOMER)).isFalse();
		assertThat(validator.isHeldBy(UNKNOWN, CUSTOMER)).isFalse();
	}

	// Fails closed: a missing customer ID never matches a missing holder.
	@Test
	void nobodyHoldsAnAccountWithoutAHolder() {
		assertThat(validator.isHeldBy(NO_HOLDER, null)).isFalse();
		assertThat(validator.isHeldBy(ACTIVE, null)).isFalse();
	}
}
