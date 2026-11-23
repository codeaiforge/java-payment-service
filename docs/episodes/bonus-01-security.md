# Bonus Episode 01 — Security

Git tag: `bonus-01-security` · Builds on: [Episode 20 — Final Architecture Review](20-final-review.md) · Next: [Bonus Episode 02 — Reconciliation](bonus-02-reconciliation.md)

## Goal

Until now anyone who could reach the service could create, read, execute
and cancel any payment. A reviewer of a payment service asks about this
first.

The business rule: only an authenticated customer may use the API, and a
customer may only act on payments from accounts they hold. They may:

- create a payment from an account they hold
- view, execute and cancel only their own payments

## Two different "authorizations"

The word is already taken. Episode 12 introduced **payment authorization**:

| | Payment authorization (Episode 12) | Access control (this episode) |
|---|---|---|
| Question | May *this payment* go ahead? | May *this caller* touch *this payment*? |
| Who decides | An external authorization system | This service, from the account holder |
| Port | `PaymentAuthorizationPort` | none: the answer comes from `AccountEnquiryPort` |
| When | Executing, after the 202 | Every request, before anything else |
| Refused | `PaymentAuthorizationException`, payment stays `CREATED` | `403` or `404`, nothing happens |

Episode 12's names stay as they are. In this episode "authorization" means
Episode 12; "access" means this one.

## Authentication: who is calling?

[`config/SecurityConfiguration`](../../src/main/java/com/example/payment/config/SecurityConfiguration.java)
makes the service an **OAuth2 resource server**. Every request needs a JWT
bearer token, and the token's `sub` is the customer ID:

```java
http.authorizeHttpRequests(requests -> requests
        .requestMatchers("/actuator/health", "/actuator/prometheus", "/v3/api-docs/**",
                "/swagger-ui.html", "/swagger-ui/**").permitAll()
        .anyRequest().authenticated())
    .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
```

Health, metrics and the API docs stay public: the container platform,
Prometheus and a developer need them without a token. `/swagger-ui.html` is
listed too, because it is the address Episode 20 documented: it redirects to
`/swagger-ui/index.html`.

The service only **consumes** tokens. There is no user table, no login
endpoint and no roles. Issuing tokens is an identity provider's job.

### Where do tokens come from locally?

In a real deployment the service checks signatures against the identity
provider's public keys. Spring Boot fetches them from
`spring.security.oauth2.resourceserver.jwt.issuer-uri`, set from outside as
in Episode 19.

Locally nothing like that runs. With the `local` Spring profile, tokens are
signed with a shared key (HS256) from
[`application-local.properties`](../../src/main/resources/application-local.properties):

```java
@Bean
@Profile("local")
JwtDecoder localJwtDecoder(@Value("${payment.security.local-jwt-secret}") String secret) {
    return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256")).build();
}
```

`./mvnw spring-boot:run` (the plugin's `<profiles>` in `pom.xml`) and the
compose `app` service (`SPRING_PROFILES_ACTIVE: local`) turn the profile on.

Episode 19 said "no profile per environment", and this is the one
exception. Its reason doesn't apply here. A forgotten `ACCOUNT_SYSTEM_URL`
fails safely: `localhost` doesn't answer. A forgotten secret would not. If
the local key sat in `application.properties`, a deployment that forgot to
override it would accept any token signed with a key published in this
repository. In the profile, a deployment that sets neither the profile nor
an issuer has no `JwtDecoder`, and the service **does not start**.

### Authentication never leaves the web adapter

`PaymentController` reads the caller from the request, not from the body:

```java
@GetMapping("/{paymentId}")
public PaymentResponse get(@PathVariable String paymentId, Principal caller) {
    return PaymentResponse.from(getPaymentUseCase.getPayment(new GetPaymentQuery(paymentId, caller.getName())));
}
```

`Principal` is plain `java.security`. For a JWT, Spring Security sets its
name to the `sub`. The application gets a `String customerId` on the
command or query and never sees a token, a `SecurityContext` or an
`Authentication`. A new rule in `ArchitectureTest` holds that:

```java
noClasses().that().resideInAnyPackage(DOMAIN, "com.example.payment.application..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework.security..");
```

The customer ID is never in the request body. A client could put anyone's
ID there.

## Access control: may they touch it?

Who holds an account is something only the account system knows, like
whether the account is active. So the answer comes through the existing
port, which now returns the holder too:

```java
public interface AccountEnquiryPort {
    Optional<Account> findAccount(AccountId accountId);

    record Account(AccountStatus status, String holderId) {
    }
}
```

`AccountResponse` gets `holderId`, and every account in
[`wiremock/mappings/accounts.json`](../../wiremock/mappings/accounts.json)
has one. `CUST-1001` holds every account except `ACC-30001`, which belongs to
`CUST-2002`.

[`AccountStateValidator`](../../src/main/java/com/example/payment/application/validation/AccountStateValidator.java)
already asked the account system about both accounts. It now checks the
source account's holder first:

```java
Account sourceAccount = find(source, "Source");
// Ownership before state: a caller who doesn't hold the account learns nothing about whether it is active.
if (!isHeldBy(sourceAccount, customerId)) {
    throw new AccessDeniedException(source);
}
```

Only the source account matters. Paying *to* someone else's account is
what payments are for.

`isHeldBy` fails closed: a missing customer ID never matches a missing
holder, so two blanks can't grant access.

Reading, executing and cancelling use the same check on the payment's
source account. The check is a filter on the lookup, so a payment that isn't
yours is simply not found:

```java
Payment payment = paymentRepository.findById(paymentId)
        // Another customer's payment is not found, not forbidden: a 403 would confirm that the ID exists.
        .filter(p -> accountStateValidator.isHeldBy(p.sourceAccountId(), command.customerId()))
        .orElseThrow(() -> new PaymentNotFoundException(paymentId));
```

The check runs before the state check, so another customer can't learn a
payment's status from a `409` either.

### Errors

| Situation | Status | Exception |
|---|---|---|
| No token, expired token, bad signature | `401` | none: Spring Security answers before the controller |
| Creating from an account the caller doesn't hold | `403` | `AccessDeniedException` (new, `application/exception`) |
| Reading, executing or cancelling another customer's payment | `404` | `PaymentNotFoundException`, same message as a payment that doesn't exist |

Why `403` for one and `404` for the other? The caller typed the account
number in the create request. Saying "that's not your account" tells them
nothing new. A payment ID is a random UUID. Answering `403` would confirm it
exists, so `404` reveals nothing.

## Asynchronous processing: the consumer doesn't check again

`POST /execute` only queues the payment (Episode 17). The Kafka consumer
runs `ExecutePaymentService` later, and nobody is calling it then:

```java
// No caller, so no customer ID: access was checked when the execution was requested
// (RequestPaymentExecutionService), and only that service publishes to this internal topic.
executePaymentUseCase.executePayment(new ExecutePaymentCommand(message.paymentId(), null));
```

The access check happens in `RequestPaymentExecutionService`, while there
is a caller. The message carries only the payment ID, as before. Checking
again in the consumer would need a customer ID in the message, and that
would only repeat what the publisher already checked. The trust boundary is
the HTTP request, not the topic. Anyone who can write to the topic is
already inside the service's infrastructure.

## Idempotency is per customer

Episode 10 stored the client's `Idempotency-Key` as it came. Two customers
who both send `Idempotency-Key: 1` would collide. The second would get
`422`, or, with the same body, the first customer's payment back. The
replay is answered before any check, so that would be a leak.

The stored key is now scoped to the customer:

```java
String key = sha256(command.customerId(), clientKey);
```

The same hash function already makes the request fingerprint. The result
is 64 characters, so it fits the `VARCHAR(255)` column and needs no
migration. The table holds no customer IDs. Keys stored before this episode
no longer match, which only matters for a retry that spans the upgrade.

## What changed

```text
pom.xml                                  + spring-boot-starter-security-oauth2-resource-server
                                         + spring-boot-starter-security-oauth2-resource-server-test (spring-security-test)
                                         + spring-boot-maven-plugin <profiles>local</profiles>
config/SecurityConfiguration             new: resource server, public paths, local JwtDecoder
application-local.properties             new: the local signing key
docker-compose.yml                       payment-service: SPRING_PROFILES_ACTIVE=local
application/exception/AccessDeniedException  new -> 403
application/port/secondary/AccountEnquiryPort  findStatus -> findAccount, returns Account(status, holderId)
application/validation/AccountStateValidator   + ownership of the source account, isHeldBy
application/usecase/command/*Command, query/GetPaymentQuery  + customerId
application/service/*                    Get, Cancel, RequestPaymentExecution: other customers' payments are not found
                                         Create: key scoped to the customer
infrastructure/.../feign/AccountResponse + holderId
infrastructure/.../web/PaymentController reads Principal, passes customerId
infrastructure/.../web/PaymentExceptionHandler  + AccessDeniedException -> 403
infrastructure/.../messaging/PaymentProcessingConsumer  customerId null, with the reason
wiremock/mappings/accounts.json          + holderId
http/payments.http                       + Authorization on every request, Bonus 01 requests
ArchitectureTest                         + coreDoesNotDependOnSpringSecurity
```

No migration: B2 takes `V4`.

## Later addition: the operator scope

After bonus 13, the service gets endpoints for the people who run it, not
customers, under `/operations/**`. Those need `payments.operate` in the
token's `scope` claim. Spring maps the claim to the `SCOPE_payments.operate`
authority, and `SecurityConfiguration` asks for it on that path only. A
customer token there is `403`. Every other endpoint is unchanged: a valid
token is enough, and its `sub` is still the customer ID.

Locally, `@operatorToken` in [http/payments.http](../../http/payments.http)
carries the scope, signed with the same local key. One scope covers both
looking and acting; split it when some operators should only look.

## Known shortcuts

- **No customer ID in logs or metrics** (Episode 15). Nothing new is logged.
  The `403` detail names the account the caller sent, never a customer.
- **Every read asks the account system.** `GET /payments/{id}` now costs one
  account lookup, and when the account system is down a read is `503`
  instead of `200`. Storing the holder on the payment at creation would
  avoid that, but it would go stale if an account changes hands. Cache
  holders if the load matters.
- **An unknown source account is still `400`, not `403`.** It tells the
  caller the account number doesn't exist, which an attacker can use to
  enumerate account numbers. Answer `403` for both when that matters.
- **Swagger UI can't send a token.** The page and the contract are public,
  but "Try it out" gets `401`. Use [http/payments.http](../../http/payments.http)
  or curl, or declare a bearer `@SecurityScheme` when a client team needs it.
- **The local tokens never expire.** They are for local use only. A real
  identity provider issues short-lived tokens with `exp`, `iss` and `aud`,
  and the decoder built from `issuer-uri` checks the issuer.

## Try it

```bash
./mvnw spring-boot:run
```

The tokens are at the top of [http/payments.http](../../http/payments.http).

1. `curl -i localhost:8080/payments/00000000-0000-0000-0000-000000000000`:
   `401`, `WWW-Authenticate: Bearer`.
2. `curl -s localhost:8080/actuator/health`: `{"status":"UP"}`, no token.
3. Run the "Bonus 01" requests in `http/payments.http`. Create as
   `CUST-1001`, then read and cancel the payment as `CUST-2002`: `404` both
   times. Pay from `ACC-10001` as `CUST-2002`: `403`. Reuse
   `CUST-1001`'s key as `CUST-2002`: a new payment.

## Tests

| Test | Change |
|---|---|
| `AccountStateValidatorTest` | **+5.** Source held by someone else is `AccessDeniedException`, checked before state; destination may be anyone's; `isHeldBy` fails closed. |
| `CreatePaymentServiceTest` | **+3.** `403` from another customer's account; the same key from two customers is two payments; another customer can't replay my key. |
| `GetPaymentServiceTest`, `CancelPaymentServiceTest`, `RequestPaymentExecutionServiceTest` | **+1 each.** Another customer's payment is `PaymentNotFoundException` and nothing changes or is queued. All without Spring. |
| `PaymentControllerPortTest` | The customer ID reaches every command and query from the `Principal`. `AccessDeniedException` is `403`. |
| `AccountEnquiryFeignAdapterTest` | The holder is read from the account system's JSON. |
| `ArchitectureTest` | **+1.** `domain` and `application` don't depend on Spring Security. |
| `PaymentEndToEndTest` | **+6.** `401` without a token or with a forged one; a token signed with the local key works; health, metrics and docs are public; `403` creating from another customer's account; `404` on get, execute and cancel of another customer's payment. Every other request carries `jwt()` for `CUST-1001`. |

165 tests, passing.
