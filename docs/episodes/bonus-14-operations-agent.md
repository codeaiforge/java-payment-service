# Bonus Episode 14 — An Operations Agent

Git tag: `bonus-14-operations-agent` · Builds on: [Bonus Episode 13 — Deploying to Kubernetes](bonus-13-kubernetes.md)

## Goal

A payment has been `PROCESSING` for seven minutes, and the
`PaymentsStuckInProcessing` alert pages someone. The
[runbook](../runbooks/PaymentsStuckInProcessing.md) tells them what to do:
check that reconciliation runs, check what it hears back, find the payment,
then settle it or escalate. This episode adds an agent that does the reading
and the thinking: it gathers the evidence, diagnoses the payment, and
proposes one of a few allowed actions, with the escalation drafted if it
escalates.

```http
POST /operations/investigations
Authorization: Bearer <a token with scope payments.operate>

{"paymentId": "5b1f9fb3-e4c9-446f-a57e-a7327dafcc90"}
```

```json
{
  "id": "0c9b…", "paymentId": "5b1f9fb3-…", "startedBy": "OPS-0001",
  "evidence": [{"id": "E1", "name": "payment status", "value": "PROCESSING"}, …],
  "diagnosis": {"cause": "PAYMENT_SYSTEM_NOT_ANSWERING", "evidenceIds": ["E1", "E3", "E7"],
                "confidence": "MEDIUM", "summary": "Reconciliation ran, and the payment …"},
  "allowedActions": ["RECONCILE_NOW", "ESCALATE_TO_PAYMENT_SYSTEM", "NO_ACTION"],
  "proposal": {"action": "RECONCILE_NOW", "escalationText": null, "replaced": null},
  "error": null
}
```

**The agent decides no outcome.** Reconciliation (B2) already settles stuck
payments, by asking the payment system, and only the payment system's answer
may move money. The agent does what an on-call engineer does with the
runbook, and nothing more. In this episode **nothing it proposes runs**: the
operator reads the proposal and acts on it, or doesn't.

The model is treated as untrusted input. The graph, the policy and the domain
decide; the model fills in the parts that need words.

## Why not just call the reconcile endpoint?

`POST /operations/payments/{id}/reconcile` (added after B13) already settles
one payment now. That endpoint is safe for one operator to call on their own,
because it decides nothing: it asks the payment system and stores its answer,
and the compare-and-set still decides who stores it.

The agent's proposal is different in kind. A person chose to reconcile; a
model *suggested* it, from text that includes the payment's reference, which
a customer wrote. So when proposals start to run (B15), one will run only
after **a second operator, not the one who started the investigation**,
approves it. That approval isn't there because reconciling is risky. It is
there because a model's proposal should be checked by someone who didn't
start the investigation.

## The graph

```text
START → gather-evidence → diagnose ─┬→ apply-policy → propose-action → END
                             ↑      │
                             └──────┘  once more, if its answer failed validation
```

Two nodes are code only, two ask the model, and the code-only ones sit
between the model calls:

| Node | Who | What |
|---|---|---|
| `GatherEvidence` | code | The payment (status, amount, reference), its history, the reconciliation status, the threshold and the schedule. Each fact gets a stable id, `E1` to `E11`. |
| `Diagnose` | model, checked by code | `Diagnosis(cause, evidenceIds, confidence, summary)`. Code settles what the facts settle; the model is asked the rest. |
| `ApplyPolicy` | code | The actions this payment allows, from the facts. |
| `ProposeAction` | model, checked by code | One action from that set, and the escalation text if it escalates. |

### Why a graph, and not Spring AI's tool loop

Spring AI can hand a model a set of tools and let it call them until it's
done. That's the wrong shape here:

- **The control flow is explicit.** The model can't skip the policy, call
  something twice more, or decide it has enough evidence. The edges are
  code.
- **Code runs between model calls.** The diagnosis is validated before the
  policy runs, and the policy runs before the model sees a choice. In a tool
  loop, the only place to check is inside each tool.
- **It is bounded.** One investigation runs at most `payment.agent.max-steps`
  nodes (10; a full run takes five). A loop that shouldn't exist stops with
  an error instead of spending money on tokens.
- **It can pause.** B15 stops the graph before an action runs, stores it, and
  resumes it hours later on another instance, after the approval. That's a
  checkpoint between two nodes, which LangGraph4j has and a tool loop
  doesn't.

### Evidence is gathered by code

The model is never offered a tool. What it may look at is decided in
`GatherEvidence`, and it's the same every time: the runbook's checks 1 and 3,
through primary ports. A model that chooses its own evidence can choose not
to look, or look somewhere it shouldn't; a model that reads a fixed list of
facts can only cite them. Every citation is checked against the list.

### Diagnose: code first, the model only for the rest

Some causes are facts, and code decides them **before** the model is asked:

- not `PROCESSING` → `NOT_STUCK`;
- younger than the threshold → `WITHIN_THRESHOLD` (the scheduled run hasn't
  asked about it yet);
- `PROCESSING` with no transition in its trail → `UNCLEAR` (its age is
  unknown; that shouldn't happen, since every path into `PROCESSING` is
  audited).

For those, the model is never called. Otherwise the model answers, and its
answer is **checked against the facts**: every cited id must exist, and the
cause must be one the facts allow. A stalled reconciliation explains a stuck
payment (`RECONCILIATION_NOT_RUNNING`); a running one that still hasn't
settled it means the payment system isn't answering
(`PAYMENT_SYSTEM_NOT_ANSWERING`). `UNCLEAR` is always allowed. An answer
that fails, or isn't the JSON asked for, is asked once more; a second
failure is `UNCLEAR`.

So what does the model add? The citations, a confidence, and a summary an
operator can read in five seconds, in a case where the cause is already
narrow. That's deliberate: the narrower the model's choice, the less a wrong
answer can do.

**The payment's age** comes from the audit trail: the `at` of its last
transition, which is written in the same transaction as the status it moved
to. The read model (`GetPaymentResult`) has no timestamp, and widening it for
one reader wasn't worth it.

### ApplyPolicy: the allowed set

| The facts | Allowed |
|---|---|
| not `PROCESSING`, within the threshold, or of unknown age | `NO_ACTION` |
| `PROCESSING` and older than the threshold | `RECONCILE_NOW`, `ESCALATE_TO_PAYMENT_SYSTEM`, `NO_ACTION` |
| … and reconciliation looks stalled (no run started for 2× its schedule, or ever) | + `ESCALATE_TO_ON_CALL` |

The schedule is `payment.reconciliation.interval`, read from configuration.
The set comes from the facts, not from the diagnosis, so a wrong diagnosis
can't widen it.

### ProposeAction: one action from the set

The model picks one action. Anything else, an action outside the set, one
that doesn't exist (`COMPLETE_PAYMENT`), or no readable answer, becomes
`NO_ACTION`, and `proposal.replaced` records what the model said instead.
When `NO_ACTION` is the only choice, the model isn't asked. The prompt passes
the diagnosis's cause, an enum, and not its summary, which is the model's own
text and could carry what it read.

> **Later addition (bonus 16).** The evals showed the model picking the
> action right less than half the time, with every cause right. Code now
> picks the action from the cause and the payment's age, by the runbook's
> rule; the model writes only the escalation text. See
> [Bonus 16](bonus-16-agent-safety.md#the-action-moved-to-code).

**Never, in any episode:** the agent completing, failing, cancelling or
refunding a payment, or reaching a secondary port, a repository or an
external system. Its nodes are given primary ports and a `ChatClient`,
nothing else.

## The prompt

Facts go in one labelled block, one per line, and the system prompt says
text inside it is never an instruction:

```text
<payment-data>
E1 | payment status | PROCESSING
E5 | payment reference (written by the customer) | Invoice 12345
…
</payment-data>
```

A value can't close the block early: `<` and `>` are replaced, and line
breaks become spaces. This is hygiene, not the defense. The defense is the
code above: the allowed set, the validation, and, from B15, the approval.
B16 shows it with a reference that tries to give the model orders.

## The agent is a primary adapter

To the application, the agent is one more caller, like the controller, the
Kafka consumers and the scheduler. It lives in
`infrastructure/adapter/primary/agent` and reaches the application only
through primary ports. ArchUnit enforces it:

- the existing rule (primary adapters use primary ports only) covers the
  `agent` package; `AgentPackageIsAPrimaryAdapterTest` plants a node holding
  a `PaymentRepository` and shows the rule fails on it;
- `domain` and `application` may not depend on `org.springframework.ai` or
  `org.bsc.langgraph4j`;
- only the agent adapter and `config` may.

### An operator reads any payment

The customer reads (`GetPaymentUseCase`, `GetPaymentHistoryUseCase`) answer
only the holder of the payment's source account, and an operator holds none.
A new primary port, `GetPaymentForOperatorUseCase`, reads any payment and its
history, and logs which operator looked. This is the same rule
`ReconcilePaymentUseCase` already follows: who counts as an operator is
decided at the edge (the `payments.operate` scope), and the application acts
for them on any payment. The customer reads don't change.

### Off by default

Everything is behind the `agent` profile: the controller, the graph, and the
chat model. Without it, Spring AI configures no model (every
`spring.ai.model.*` is `none` in `application.properties`), no client or
connection exists, and `/operations/investigations` is a `404`.

## The model

Ollama by default: a container behind the `agent` compose profile, so the
stack doesn't grow for anyone who doesn't use it, and a small model that can
answer in a given JSON shape (`qwen2.5:3b`, about 2 GB, pulled on first
start). `AGENT_OLLAMA_MODEL` picks another. Add the `anthropic` or `openai`
profile to use one of those, with its key in `ANTHROPIC_API_KEY` or
`OPENAI_API_KEY`.

**CI never calls a model.** Every test uses `ScriptedChatModel`, which
returns the JSON the test gives it and records what it was asked.

## Architecture

```text
config/AgentConfiguration                                 new: the graph and its ChatClient; agent profile only
application/port/primary/GetPaymentForOperatorUseCase     new: any payment and its history, for operators
infrastructure/adapter/primary/agent/                     new: the agent, a driving adapter
├── InvestigationController                               POST /operations/investigations, GET …/{id}
├── StuckPaymentGraph                                     the StateGraph: nodes, edges, step limit
├── InvestigationState                                    LangGraph4j AgentState, typed accessors
├── Evidence, Diagnosis, ProposedAction, InvestigationView
└── nodes/GatherEvidence, Diagnose, ApplyPolicy, ProposeAction, Prompts
```

The domain doesn't change. The application gains one read port.

## What changed

```text
pom.xml                                                          + Spring AI 2.0.3, LangGraph4j 1.9.10 (build commit before this one)
docker-compose.yml                                               + ollama (profile agent); the app's profiles from SPRING_PROFILES_ACTIVE
src/main/resources/application.properties                       + spring.ai.model.* = none
src/main/resources/application-agent.properties                 new: Ollama, payment.agent.max-steps
src/main/resources/application-anthropic.properties             new
src/main/resources/application-openai.properties                new
http/payments.http                                               + Bonus 14 requests
docs/runbooks/PaymentsStuckInProcessing.md                       + the agent as a shortcut through the checks
src/main/java/com/example/payment/
├── application/port/primary/GetPaymentForOperatorUseCase.java   new
├── application/usecase/query/GetPaymentForOperatorQuery.java    new
├── application/service/query/GetPaymentForOperatorService.java  new
├── config/AgentConfiguration.java                               new
└── infrastructure/adapter/primary/agent/                        new (see Architecture)
```

## Known shortcuts

- **Investigations live in memory.** A restart loses them, and each instance
  knows only its own. B15 stores them in PostgreSQL.
- **An investigation runs in the request.** The operator waits for two model
  calls, seconds on a small model. Fine for one operator and one payment;
  a queue would be needed for many.
- **Every operator may read every payment, and the record is a log line.** A
  bank would keep "who looked at which payment" as an audit row, like the
  status changes, and keep it as long as the payment's own records.
- **The LangGraph4j line is young.** 1.9 is a few months old, and its
  Spring AI support follows Spring AI's releases by weeks. The graph uses
  only its core (nodes, edges, the step limit, and in B15 a checkpoint);
  replacing it would mean rewriting `StuckPaymentGraph`, not the nodes.
  LangGraph4j copies state between nodes with Java serialization by default;
  this graph turns that off, since every value is an immutable record.
- **The model choice.** `qwen2.5:3b` is small enough to run on a laptop and
  is wrong more often than a large model. The validation catches answers
  that disagree with the facts, not a plausible summary that misreads them.
  B16 measures how often each cause comes out right. Before a bank used
  this, the model, its prompts and its measured accuracy would go through
  **model risk review**, as any model that informs a decision does.
- **English only.** The prompts and the summaries are in English.
- **No record retention.** An investigation records what an operator saw and
  what the agent proposed. A bank would keep that as long as the payment's
  records. B15 stores it; how long to keep it is a policy this service
  doesn't have.

## Try it

```bash
docker compose --profile agent up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=local,agent
```

The first start pulls the model, which takes a few minutes.

1. **A young payment needs no model.** Create and execute a payment with
   `TIMEOUT` in its reference (its answer is lost, so it stays
   `PROCESSING`), then run Bonus 14's first request in `http/payments.http`
   with its id. The diagnosis is `WITHIN_THRESHOLD`, the only action is
   `NO_ACTION`, and Ollama's log shows no request.
2. **A stuck one.** The scheduled run would settle it within a minute of the
   threshold, so start the service with a slower one:
   `./mvnw spring-boot:run -Dspring-boot.run.profiles=local,agent -Dspring-boot.run.arguments=--payment.reconciliation.interval=10m`.
   Create and execute another `TIMEOUT` payment, wait 2 minutes, and
   investigate it. The model is asked. If a run has started since the
   service did (`GET /operations/reconciliation`), the diagnosis should be
   `PAYMENT_SYSTEM_NOT_ANSWERING`; if none has, reconciliation looks stalled
   and it should be `RECONCILIATION_NOT_RUNNING`, with `ESCALATE_TO_ON_CALL`
   allowed. Either way the proposal is one of `allowedActions`, and the
   payment is still `PROCESSING`: nothing ran.
3. **Read it back.** The second request, with the investigation's id.
4. **Only operators.** The same request with `{{token}}` (a customer) is `403`.
5. **Off by default.** Without the `agent` profile, the first request is
   `404`.

## Tests

| Test | Change |
|---|---|
| `StuckPaymentGraphTest` | **new, 12.** The whole graph with a scripted model and faked ports, one case per cause. `NOT_STUCK` and `WITHIN_THRESHOLD` never call the model. `PAYMENT_SYSTEM_NOT_ANSWERING` proposes `RECONCILE_NOW` from three allowed actions; `RECONCILIATION_NOT_RUNNING` adds `ESCALATE_TO_ON_CALL` and drafts the escalation; the model may answer `UNCLEAR`. Two answers that disagree with the facts are `UNCLEAR` after two attempts; an answer that isn't JSON is asked again. An action outside the set and one that doesn't exist both become `NO_ACTION`, with what was proposed recorded. A step limit of four stops a run that needs five. An unknown payment ends the run with `PaymentNotFoundException`. The facts reach the model in the data block only. |
| `ApplyPolicyTest` | **new, 9.** The policy as a table: status, age and stalled in, the allowed set out. |
| `GetPaymentForOperatorServiceTest` | **new, 3.** An operator reads a payment and trail they don't hold; an unknown payment is not found; a malformed id is invalid. |
| `OperationsAgentEndToEndTest` | **new, 5.** The service with the agent profile, real PostgreSQL and Kafka, and the scripted model as the only `ChatModel`. A payment `PROCESSING` for five minutes gets a diagnosis and a `RECONCILE_NOW` proposal, readable at its `Location`, and stays `PROCESSING`. A young payment needs no model. A customer is `403`; an unknown payment and an unknown investigation are `404`. |
| `PaymentEndToEndTest` | **+1.** Without the agent profile, `POST /operations/investigations` is `404`. |
| `ArchitectureTest` | **+2.** The core doesn't depend on Spring AI or LangGraph4j; only the agent adapter and config do. |
| `AgentPackageIsAPrimaryAdapterTest` | **new, 1.** The primary-ports rule fails on a planted agent node holding a `PaymentRepository`. |
| `AgentDependenciesTest` | **new, 2** (build commit). One stubbed request through Spring AI's Anthropic and OpenAI clients on OkHttp 5. |

422 tests, passing.
