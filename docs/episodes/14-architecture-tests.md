# Episode 14 — Architecture Tests

Git tag: `episode-14-architecture-tests` · Previous: [Episode 13 — Transactions and Audit](13-transactions-audit.md) · Next: [Episode 15 — Observability](15-observability.md)

## Goal

Thirteen episodes drew boundaries by convention: the domain imports no
Spring, the application never reaches into an adapter, controllers only
translate. Nothing stopped the next change from crossing one. A single
`import` is enough, and it compiles fine.

This episode turns those conventions into tests. Break a boundary and
`./mvnw test` fails, naming the class and line:

```text
Rule 'no classes that reside in a package 'com.example.payment.domain..' should depend on
classes that reside in any package ['org.springframework..', 'jakarta.persistence..', 'feign..']'
was violated (1 times):
Static Initializer <com.example.payment.domain.valueobject.AccountId.<clinit>()> calls constructor <org.springframework.util.StopWatch.<init>()> in (AccountId.java:4)
```

The architecture becomes executable documentation: the rules below *are*
the dependency diagram, and they can't drift from the code.

## The rules

All in one test, `ArchitectureTest`, using [ArchUnit](https://www.archunit.org/).
It reads the compiled classes (`target/classes`, not tests) and checks every
dependency between them.

| Rule | Why |
|---|---|
| `domain` must not depend on `infrastructure` | The business rules must not know how they are stored or served. |
| `domain` must not depend on Spring, JPA or Feign | The domain must run, and be tested, without any framework. |
| `application` must not depend on `infrastructure` | Use cases talk to ports. Adapters implement them. The arrow points inward. |
| `domain` must not use `@Entity` classes | The JPA model is a storage detail. `PaymentEntityMapper` converts at the edge. |
| `web` must not call domain behaviour | See below. |
| `web` must not read domain constants | See below. |

`application` *may* use Spring: `@Service` and `TransactionOperations` are
wiring and transaction boundaries, not business rules. The rules only forbid
what the spec forbids.

## "Web adapters must not contain business rules"

You can't test for "a business rule" directly, so the test checks what one
looks like in code. The controller receives a `Payment` back from the use
cases and reads it to build a `PaymentResponse`. That's translation, and it's
allowed. What isn't allowed:

```java
// a decision: is this payment allowed to move?
if (payment.status() == PaymentStatus.CREATED) {   // reads a domain constant
    payment.authorize();                            // calls a state change
}
new AccountId(request.sourceAccountId());           // builds and validates a domain object
```

So, from the `web` package:

- **No calls to domain constructors, static factories or `void` methods.**
  These create domain objects or change their state. Reading accessors
  like `payment.status()` or `accountId.value()` is fine.
- **No reads of domain fields.** In practice these are enum constants like
  `PaymentStatus.COMPLETED`. Comparing against one is a decision about
  status, and decisions about status belong to `Payment`.

## What changed

```text
pom.xml                                              + archunit-junit5 (test)
src/test/java/com/example/payment/
└── ArchitectureTest.java                            new
```

No production code changed. Every rule passed on the first run, which is
the point: Episodes 01–13 kept the boundaries, and now nothing can quietly
cross them.

## Known shortcuts

- **The web rule is a proxy.** Logic built from `payment.amount()` and
  plain Java would still pass. A stricter rule, "web must not depend on
  the domain at all", needs the command use cases to return an application
  result instead of `Payment`. That changes three ports and their tests.
  It's worth doing when a second adapter needs the same results.
- **`domain` must not depend on `config`**, which §3 also says, isn't a
  separate rule. `config` is Spring wiring, so the framework rule already
  catches it in practice.
- **No rules for `messaging` yet.** That package doesn't exist until
  Episode 17.

## Later addition

After bonus 13, one more rule: **primary adapters use primary ports only.**
Web, messaging and scheduling reach the application through `port.primary`,
its commands, results and exceptions, never a secondary port, a service, a
validator or a secondary adapter. They already did; now the build says so.
The layered rule can't: to it, any adapter may see the whole application.

## Try it

Make the domain import Spring:

```java
// src/main/java/com/example/payment/domain/valueobject/AccountId.java
public record AccountId(String value) {
	static Object leak = new org.springframework.util.StopWatch();
	...
```

```bash
./mvnw clean test -Dtest=ArchitectureTest
```

The test fails with the message under Goal. Remove the line to make it
pass again. (Use `clean`: Maven can skip recompiling a file changed in the
same second as its last build.)

## Tests

| Test | What it proves |
|---|---|
| `ArchitectureTest` | **New.** Six rules: domain independent of infrastructure and frameworks; application independent of infrastructure; no `@Entity` in the domain; web reads the domain but never creates, changes or compares it. |
| All others | Unchanged. |
