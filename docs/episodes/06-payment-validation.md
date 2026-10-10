# Episode 06 — Payment Validation

Git tag: `episode-06-validation` · Previous: [Episode 05 — Query a Payment](05-query-payment.md) · Next: [Episode 07 — External Account System](07-account-system.md)

## Goal

The bank now caps a single payment at **10,000.00** in its currency. A payment
over that is rejected with `400`:

```text
Payment amount must not exceed 10000.00 EUR
```

That one new rule is the excuse for the real lesson. A payment request is
checked in four different places, for four different reasons. This episode
names them and gives application validation its own package:

```text
application/validation
```

## Four kinds of validation

| Kind | Where | Question it answers | Rules here |
|---|---|---|---|
| **Web/request** | `PaymentRequest` (`@NotBlank`, `@NotNull`) | Is the request well-formed enough to read? | every field is present |
| **Domain invariants** | value objects and `Payment` | Can this thing exist at all? | positive amount, supported currency, different accounts, valid reference |
| **Application validation** | `application/validation` | Does the bank accept this payment, right now? | maximum allowed amount, account state |
| **External-system validation** | behind `AccountEnquiryPort` | What does the system that owns the data say? | does the account exist, what is its status |

The last two meet in `AccountStateValidator`: the external system supplies
the facts (the account is `BLOCKED`), and the application decides what they
mean (a blocked account can't take part in a payment).

A rule belongs in the **domain** if breaking it makes the object meaningless.
A payment of `-5` EUR, or one from an account to itself, isn't a payment.
A rule belongs in the **application** if the object makes sense but the bank
won't accept it. A 50,000 EUR payment is a perfectly good payment; this
service just doesn't take it. That's why the limit is not in `Money` or
`Payment`: a payment restored from the database must never fail to load
because the limit later went down.

## The flow

```text
PaymentRequest           web: fields present?                   (infrastructure)
     |
     v
CreatePaymentService
     |
     |  1. Payment.create(...)              domain invariants
     |  2. PaymentAmountLimitValidator      application policy    new
     |  3. AccountStateValidator            account system        new (moved)
     |  4. PaymentRepository.save
     v
```

The order is deliberate. Steps 1 and 2 run locally and cost nothing; step 3
calls another system. A request that's going to fail anyway never reaches the
account system. Before this episode the service checked the accounts *before*
building the `Payment`, so a payment from an account to itself still made two
account enquiries. Now it makes none.

## What changed

```text
src/main/java/com/example/payment/
├── application/
│   ├── validation/
│   │   ├── PaymentAmountLimitValidator.java     new: maximum allowed amount
│   │   └── AccountStateValidator.java           new: moved out of CreatePaymentService
│   └── service/command/
│       └── CreatePaymentService.java            builds the Payment first, then validates
└── infrastructure/adapter/primary/web/
    └── PaymentRequest.java                      comment only

src/test/java/com/example/payment/
├── application/service/command/
│   └── CreatePaymentServiceTest.java            + limit cases, + no enquiry for any local failure
└── infrastructure/adapter/primary/web/
    └── PaymentControllerTest.java               + over-limit is 400
```

The domain, the ports and every adapter other than a comment did not change.

## Why

### One validator per rule, not one validator

```java
amountLimitValidator.validate(payment.amount());
accountStateValidator.validate(payment.sourceAccountId(), payment.destinationAccountId());
```

A single `PaymentValidator` with every check in it is tempting and goes bad
quickly. The two rules here have nothing in common:

- **Different dependencies.** The limit needs nothing. The account check needs
  `AccountEnquiryPort`. In one class, testing the limit means faking the
  account system.
- **Different futures.** Episode 07 puts a real account system behind the
  port, and `AccountStateValidator` doesn't change. Episode 12 moves limits
  behind `PaymentLimitPort`, and only `PaymentAmountLimitValidator` changes.
- **Different costs.** One is a comparison, the other is a network call.
  Keeping them separate keeps the order visible in the service.

Each validator is a plain class, not an interface. There's one implementation
of each, and nothing would be gained by hiding it.

### The service reads as the business flow

`CreatePaymentService` no longer contains any `if`. It builds the payment,
asks two validators, and saves. The rules are where their names say they are.

### The limit lives in the application, for now

```java
// SHORTCUT: one fixed limit in the payment's own currency; Episode 12 moves limits behind PaymentLimitPort
public static final BigDecimal MAX_AMOUNT = new BigDecimal("10000.00");
```

10,000.00 EUR and 10,000.00 GBP are not the same amount of money. A real
bank's limits depend on the currency, the customer and the channel, and come
from a limits system. Episode 12 adds that system behind a secondary port.
Until then, one constant keeps the rule visible.

### "Valid payment reference" was already a domain invariant

`PaymentReference` has rejected a blank or over-140-character reference since
Episode 01. A reference that breaks those rules isn't a reference, so it stays
in the domain. Not every rule in the business list belongs in
`application/validation`.

## Known shortcuts

- **Every rule failure is still an `IllegalArgumentException` and a `400`.**
  Episode 08 introduces application exceptions and maps them to `400`, `404`
  and `503` problem details.
- **One limit for every currency.** Episode 12 adds `PaymentLimitPort`.
- **Account state still comes from the stub.** Episode 07 replaces it with a
  Feign adapter to an external account system.

## Try it

```bash
./mvnw spring-boot:run
```

In [`http/payments.http`](../../http/payments.http), run the two Episode 06
requests: `10000.00` is accepted, `10000.01` is rejected.

## Tests

| Test | What it proves |
|---|---|
| `application/service/command/CreatePaymentServiceTest` | **+3 cases.** An amount exactly at the limit is accepted. The "no account enquiry" test now covers three failures instead of one: an unsupported currency, the same account on both sides, and an amount over the limit. The validators are built with `new`, with the same map-backed fake account system. No Spring, no database. |
| `infrastructure/adapter/primary/web/PaymentControllerTest` | **+1 case.** `10000.01` EUR is `400` with `Payment amount must not exceed 10000.00 EUR`. |
| Everything else | Unchanged. |
