package com.example.payment.domain.valueobject;

/**
 * Currencies this bank accepts for payments. Deliberately a closed set:
 * "a valid ISO code" is not the same thing as "a currency we support".
 */
public enum Currency {
	EUR, GBP, USD;

	public static Currency of(String code) {
		if (code == null) {
			throw new IllegalArgumentException("Currency is required");
		}
		try {
			return valueOf(code.strip().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Unsupported currency: " + code);
		}
	}

	// SHORTCUT: all supported currencies use 2 minor units; add a field when JPY-style currencies arrive
	public int minorUnits() {
		return 2;
	}
}
