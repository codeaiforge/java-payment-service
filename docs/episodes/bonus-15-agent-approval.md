# Bonus Episode 15 — Four Eyes on the Agent

Git tag: `bonus-15-agent-approval` · Builds on: [Bonus Episode 14 — An Operations Agent](bonus-14-operations-agent.md)

## Goal

In B14 the agent proposed and nothing ran. Now a proposal runs, but only
after a **second operator**, not the one who started the investigation,
approves it, and it runs **once**, whatever happens in between: a restart,
another instance taking the approval, two operators clicking at the same
moment.

```http
POST /operations/investigations/0c9b…/approve
Authorization: Bearer <OPS-0002, scope payments.operate>
```

```json
{
  "id": "0c9b…", "paymentId": "5b1f9fb3-…", "startedBy": "OPS-0001",
  "proposal": {"action": "RECONCILE_NOW", "escalationText": null, "replaced": null},
  "status": "EXECUTED", "decidedBy": "OPS-0002", "decidedAt": "2026-11-25T14:03:12Z",
  "result": "Reconciled: the payment system answered EXECUTED; the payment is COMPLETED."
}
```

Why a second person, when `POST /operations/payments/{id}/reconcile` lets
one operator do the same on their own? Because that endpoint runs a person's
decision, and the payment system's answer decides the outcome. Here a model
made the proposal, from text that includes a customer's payment reference.
The approval isn't there because reconciling is risky. It is there because
**a model's proposal should be checked by a person who didn't start the
investigation**.

## The graph pauses

```text
START → gather-evidence → diagnose ⟲ → apply-policy → propose-action ─┤ pause ├→ execute → END
```

The graph is compiled with an interrupt **before** `execute`. Starting an
investigation runs it to the pause and stops; approving it resumes the run,
and `execute` is all that's left.

The pause can last hours. In that time the instance that started it may be
redeployed, and the approval may reach another one. So the pause must
outlive the process: after every node, LangGraph4j's PostgreSQL saver writes
a **checkpoint**, the state so far, under a thread named after the
investigation. Resuming reads the last one, on whichever instance gets the
approval, with nothing in memory.

### The checkpoint tables come from Flyway

The saver can create its tables at start-up. It doesn't here
(`createTables(false)`): `V14__create_agent_checkpoints.sql` is a copy of its
DDL (`langgraph4j-postgres-saver` 1.9.10, `v2.1__init.sql`). The schema has
one owner, every change is reviewed as a migration, and this one is an
expansion: three new tables the previous release never reads, so CI's
schema-compatibility job stays green. Upgrading the library means checking
its DDL against V14 and adding a migration if it changed.

### The state is JSON, on Jackson 3

A checkpoint is the state serialized. LangGraph4j's own serializers are Java
serialization or Jackson 2; `InvestigationStateSerializer` writes JSON with
Jackson 3, as the rest of the service does, and names the type of every
key, so a checkpoint reads back into the same records it came from. Two
things its test pins down:

- **Money keeps its scale.** Read through a JSON tree, `250.00` comes back
  as `250.0`, a double. The mapper reads decimals as `BigDecimal` and keeps
  their trailing zeros.
- **An unknown key is an error.** A checkpoint written by a newer release
  would otherwise resume with part of its state silently missing.

## Exactly once lives in our table

Who may resume a run, and that only one does, is not left to the
checkpoints. An `investigation` row (`V15`) holds the proposal and its
status:

```text
PROPOSED ──approve──▶ APPROVED ──execute──▶ EXECUTED | NO_LONGER_NEEDED
    └─────reject────▶ REJECTED
```

Approving is a **compare-and-set**, the same pattern as a payment's status:

```sql
UPDATE investigation SET status = 'APPROVED', decided_by = ?, decided_at = ?
WHERE id = ? AND status = 'PROPOSED'
```

Two operators approving at once, on one instance or two, both read
`PROPOSED`, and the database lets exactly one `UPDATE` through. The winner
resumes the graph; the other gets `409`. A double click, a retried request,
a second instance: one execution.

Why not rely on the checkpoint for this? A checkpoint says where a run is,
not who may move it on. Two instances can both read "paused before
execute" and both resume, and the library has no lock between them. The
rule that must hold is ours, so it lives in our table, under our
transaction, with our test.

### The findings are kept with the decision

The row also keeps what the operators saw, as JSON: the evidence, the
diagnosis, the allowed actions and the proposal. The view is read from the
row alone. The checkpoints are there to resume a run, and the saver releases
them when it ends; the record of what was proposed and who approved it can't
depend on them.

### Four eyes

`DecideInvestigationUseCase` refuses an operator who started the
investigation, with `403`, before the compare-and-set, so the proposal stays
`PROPOSED` for someone else. The rule is in the application, not the
controller: whoever calls the port, it holds.

### Investigations live in the application

`investigation` is persistence, and the agent is a primary adapter, which
may not reach a repository (ArchUnit). So the record is an application
concept behind primary ports: `RecordInvestigationUseCase` (open, finish),
`DecideInvestigationUseCase` (approve, reject) and
`GetInvestigationUseCase`, one service, and an `InvestigationRepository`
port implemented in plain SQL. The application doesn't interpret the
proposed action: it records it, lets a second person decide, and keeps the
result. Running it stays in the adapter.

## Execute: code only, as the approver

`Execute` runs after the approval, with the approver's id, and never asks
the model:

- **First, read the payment again.** Hours may have passed. If it isn't
  `PROCESSING` any more (the scheduled run settled it, or an operator did),
  the investigation ends `NO_LONGER_NEEDED` and nothing runs. A payment
  settled between that read and the reconcile call is refused by the
  reconcile use case, and ends the same way.
- **`RECONCILE_NOW`** calls `ReconcilePaymentUseCase` with the **approver**
  as the operator, so its log names who forced the outcome. The result is
  stored as it comes: `UNKNOWN`, or `NOT_RECEIVED` on a payment younger than
  the threshold, leave the payment `PROCESSING`. That's a result, not an
  error; the payment system's answer decides, as always.
- **`ESCALATE_*`** stores the text, the target and the approver. Nothing is
  sent.
- **`NO_ACTION`** finishes.

## Architecture

```text
application/port/primary/RecordInvestigationUseCase        new: open, finish
application/port/primary/DecideInvestigationUseCase        new: approve, reject (four eyes, compare-and-set)
application/port/primary/GetInvestigationUseCase           new
application/port/secondary/InvestigationRepository         new
application/service/command/InvestigationService           new
infrastructure/adapter/secondary/persistence/InvestigationPersistenceAdapter   new: the guarded UPDATEs
infrastructure/adapter/primary/agent/
├── InvestigationController                                + approve, reject; views from the investigation row
├── StuckPaymentGraph                                      + execute, the pause, the checkpoint saver
├── InvestigationStateSerializer                           new: checkpoints as JSON (Jackson 3)
├── Findings                                               new: what the operators saw, kept with the row
└── nodes/Execute                                          new
config/AgentConfiguration                                  + PostgresSaverV2 on the service's DataSource
```

## What changed

```text
pom.xml                                                          + langgraph4j-postgres-saver
src/main/resources/db/migration/V14__create_agent_checkpoints.sql new: the saver's tables
src/main/resources/db/migration/V15__create_investigation.sql   new: investigation
http/payments.http                                               + secondOperatorToken (OPS-0002), approve, reject
src/main/java/com/example/payment/
├── application/exception/AccessDeniedException.java             + a constructor with a message
├── application/exception/InvestigationNotFoundException.java    new (404)
├── application/exception/InvalidInvestigationStateException.java new (409)
├── application/usecase/query/Investigation.java                 new
├── application/usecase/command/{Open,Decide,Finish}InvestigationCommand.java   new
├── infrastructure/adapter/primary/web/PaymentExceptionHandler.java              + the two exceptions
└── …                                                            (see Architecture)
```

B14's in-memory map of investigations is gone; so is the `error` field on a
view. A run that fails (the step limit, a database error) is now a `500`,
and leaves no investigation behind.

## Known shortcuts

- **Pending approvals never expire.** A proposal made at 3 a.m. can be
  approved a week later. The stale check stops it running on a settled
  payment, but not on one whose situation changed in other ways. A bank
  would expire a proposal after a short window (an hour, say): a scheduled
  job, like the outbox cleanup, moving old `PROPOSED` rows to `EXPIRED`
  through the same compare-and-set, so an approval racing it still loses
  cleanly.
- **Escalations aren't sent.** They are recorded with their text, target
  and approver. Sending needs a mail or pager integration, with its own
  retries; the investigation's result would then say whether it was
  delivered.
- **A crash between approval and finish leaves it `APPROVED`.** If the
  instance dies after the compare-and-set and before the result is stored,
  nothing retries it, and the row says `APPROVED` with no result. Running it
  again would be safe (a reconcile asks the payment system again; an
  escalation is only recorded), so the fix is a job that resumes `APPROVED`
  rows older than a few minutes.
- **Checkpoints are never deleted.** Each run leaves its checkpoint rows. A
  cleanup like the outbox's (B7) belongs here once the table grows.
- **Retention of investigation records.** The `investigation` row is the
  record of a model's proposal and the people who approved it. A bank keeps
  that as long as the payment's own records and makes it part of the audit;
  this service has no retention policy for it, as for nothing else.
- **Model risk review** still applies, as in B14: the approval checks one
  proposal, not how often the model is right. B16 measures that.
- **LangGraph4j's executor threads** outlive a closed application context
  (Tomcat warns about it in the tests). Harmless in a process that exits;
  worth reporting upstream.

## Try it

```bash
docker compose --profile agent up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=local,agent -Dspring-boot.run.arguments=--payment.reconciliation.interval=10m
```

1. **A proposal.** Create and execute a payment with `TIMEOUT` in its
   reference, wait 2 minutes, and start an investigation with Bonus 14's
   first request. It comes back `PROPOSED`.
2. **Four eyes.** Approve it with `{{operatorToken}}`, the operator who
   started it: `403`, and it stays `PROPOSED`.
3. **Approve it.** Bonus 15's first request, with `{{secondOperatorToken}}`.
   For `RECONCILE_NOW` the payment system answers `EXECUTED`: the
   investigation is `EXECUTED`, the payment `COMPLETED`, and
   `GET /payments/{id}/history` (as `{{token}}`) ends `PROCESSING →
   COMPLETED`. Approving again is `409`.
4. **After a restart.** Start another investigation, stop the service, start
   it again, and approve: the run resumes from PostgreSQL
   (`SELECT thread_name, is_interrupted FROM lg4jthread;`).
5. **Stale.** Start one, settle the payment by hand
   (`POST /operations/payments/{id}/reconcile`), then approve:
   `NO_LONGER_NEEDED`, and nothing ran.

## Tests

| Test | Change |
|---|---|
| `AgentApprovalTest` | **new, 6.** Two instances with the agent profile over one PostgreSQL, real HTTP, signed tokens. A proposal started on one instance is approved on the other: `EXECUTED`, the payment `COMPLETED`, one question to the payment system, and the change in the payment's history. Two approvals released together on both instances: one `200`, one `409`, one question to the payment system. The operator who started it gets `403`. A payment reconciled by hand while the proposal waited ends `NO_LONGER_NEEDED`. A rejected proposal never runs, and approving it afterwards is `409`. A restarted instance resumes a run another process paused. |
| `StuckPaymentGraphTest` | **+4.** The run stops before execute, and the approver's resume reconciles as them. A payment settled since is `NO_LONGER_NEEDED`. An escalation is recorded, not sent. A fresh graph over the same checkpoints resumes the run. Every test's checkpoints now go through the JSON serializer. |
| `InvestigationStateSerializerTest` | **new, 2.** Every key reads back as the record it was written from, amounts with their scale; an unknown key is an error. |
| `InvestigationServiceTest` | **new, 5.** Another operator approves; the one who started it may neither approve nor reject, and nothing is written; a lost compare-and-set is refused; an unknown id is not found; an approved investigation finishes only as `EXECUTED` or `NO_LONGER_NEEDED`. |
| `InvestigationPersistenceAdapterTest` | **new, 3.** Real PostgreSQL: a proposal reads back; only the first decision gets through; only an approved investigation finishes, once. |

442 tests, passing.
