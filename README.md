# quarkus-pr-wall

A **read-only** Quarkus PR review agent. It analyzes a pull request and prints a markdown report — it
**never posts, approves, or merges**. Structured decisions (classification, scoring, booleans) are made by
TypeSafe `jev`; Anthropic Opus is used only for a handful of narrow prose artifacts (a per-finding fix line,
the inconsistency sentence), always *downstream* of every gate.

Governing principle: **high threshold / high signal — silence by default.** A clean PR produces an explicit
"nothing met the review threshold" report, not a manufactured list of nits.

## Quick start

```shell
export TYPESAFE_API_KEY=...      # jev (all structured decisions)
export ANTHROPIC_API_KEY=...     # Opus (fix prose only; optional — falls back to deterministic summaries)
export GH_REPO=quarkusio/quarkus # owner/repo for the read-only gh calls

# Review a single PR at full depth (runs all deep pipelines A–G):
java -jar target/quarkus-app/quarkus-run.jar review 45678

# Review a range / set; prints one ranked table (gate prunes deep pipelines per PR):
java -jar target/quarkus-app/quarkus-run.jar review-range 45670-45680,45690

# Skip Opus and render deterministic summaries only:
java -jar target/quarkus-app/quarkus-run.jar review 45678 --no-prose

# Probe every external dependency (gh, jev, Opus) before a sweep:
java -jar target/quarkus-app/quarkus-run.jar health
```

`gh` must be installed and authenticated with a **read-only, least-privilege** token (no write/merge/secret
scope) — this is the primary prompt-injection mitigation.

## Architecture

```
facts (deterministic, gh read-only)
  → triage (one cheap jev call: cascade gate + first-pass grader)
    → deep pipelines A–G (independent, one jev call each, fan out in parallel)
      → interpretation (confidence gate + rule engine; anti-gaming invariants I-1…I-6 compiled in)
        → report (Opus fix prose + markdown)
```

- **Concern modules** (`resources/questions/<concern>.json`) — one file per concern holding the externalized
  `jev` questions *and* the declarative rules that read their answers. Each question id is a stable semantic
  slug (`breaks_callers`, `test_asserts_behavior`), unique across every set, naming what the question *is*
  rather than how it is currently worded — so instruction text can be re-tuned without re-keying the rules,
  the pipelines and the eval fixtures that reference it. Rules sit next to the ids they reference because the
  two are one reviewable unit: rename an id and every rule reading it breaks in the same diff, and
  `RulesetLint` can check a rule against exactly the ids its own file declares. The two versions stay
  separate — `version` is wire-visible and bumps only on a prompt change; `rulesVersion` bumps on a re-tune.
  Only `triage.json` has a `rules` block: the rule engine runs at triage, and the deep pipelines interpret
  their answers in Java.
- **Facts** (`facts/`) — diff/file/test classification, committer status, author history. Trust facts are
  pinned to `base.sha` and **never** placed in a `jev` state (invariants I-2, I-3).
- **Triage** (`pipelines/TriagePipeline`) — the cascade gate; decides which deep pipelines are reserved to run.
  [`TRIAGE-FLOW.md`](TRIAGE-FLOW.md) diagrams the whole path, state build through rules execution.
- **Confidence gate** (`interpret/ConfidenceGate`, `Band`) — asymmetric: only a CONFIRM-band "no issue" clears a
  concern to silence; any sub-CONFIRM risk answer routes to ≥ FLAG (I-5).
- **Rule engine** (`interpret/rules/`) — pure function over banded answers + facts; a closed, safe-by-construction
  effect set (no priority-lowering verb, guarded silence). The invariant tier is compiled in and non-overridable;
  the tunable rules live in the `rules` block of `resources/questions/triage.json`, with every number referenced
  by name from `resources/thresholds.yaml`.
- **Deep pipelines** (`pipelines/`) — A test-adequacy · B exempt-class · C breaking-change · D test-weakening ·
  E docs-missing · F domain-routing · G inconsistency. Each is fully independent (reads no other's output).
- **Combine** (`review/FindingCombiner`) — resolves the two deliberate couplings in code: A+B (exempt-class
  suppress/downgrade behind the deterministic mixed-change fail-safe) and G-corroborates-C (G never *feeds* C).
- **Orchestration** (`review/ReviewEngine`, `RangeEngine`) — Mutiny parallel fan-out; single-PR = full depth,
  range = pruned by the triage escalation set; every PR appears in the output, errored PRs included.
- **Report** (`report/`) — `ProseEnricher` (Opus, with deterministic fallback) + `MarkdownRenderer` (pure).
  `DiffCompactor` fits an oversized diff into the `jev` state budget: non-focal files are stubbed first and
  focal hunks stay byte-for-byte, so a verdict is never decided on paraphrased code. Truncating mid-diff is
  deliberately not an option — the tail is exactly where a breaking hunk hides.
- **Health** (`health/`) — `pr-review health` probes `gh`, `jev` and Opus with the cheapest call that proves
  each credential. `gh` and `jev` are required; Opus is optional, so its absence reports *degraded*, not *down*.

## Configuration

All config lives in `src/main/resources/application.yml`; secrets come from the environment.

| Env var | Purpose | Default |
|---|---|---|
| `TYPESAFE_API_KEY` | jev auth (required for live runs) | — |
| `ANTHROPIC_API_KEY` | Opus auth (optional) | — |
| `GH_REPO` | `owner/repo` for `gh` | — |
| `GH_BINARY` | path to `gh` | `gh` |
| `JEV_URL` / `ANTHROPIC_URL` | API base URLs | the public endpoints |
| `JEV_MODEL` / `ANTHROPIC_MODEL` | model ids | `jev-latest` / `claude-opus-5` |

Confidence dials (§6) and emission thresholds (I-6) live in `resources/thresholds.yaml`, referenced by name
from the rules — never inlined. The `rules` block of `resources/questions/triage.json` is the reviewable tuning
surface; a build-time `RulesetLint` fails the build on unknown references, disallowed effect verbs, or an
unguarded `silence`, so an unsafe ruleset can never ship. Rules are parsed strictly: a misspelled key
(`sevrity`) fails the load rather than being silently dropped, and each rule carries a `note` field for the
rationale JSON cannot put in a comment.

## Testing

`./mvnw test` runs the suite. It never touches the network: `NoNetworkGhClient` is a global `@Mock` over the
single `gh` chokepoint that throws on any unfixtured invocation, so a test that would reach GitHub fails loudly
instead of passing slowly.

Four layers, cheapest first:

- **Pure interpretation tests** — the rule engine, each deep pipeline's `interpret`, and the finding combiner are
  functions over hand-authored answer/fact fixtures (no network, no CDI), so every Blocker / Needs-attention /
  silence decision and every invariant is asserted directly. These are the highest-value tests.
- **Client tests** (`JevServiceWireMockTest`) — the `jev` client against a real HTTP server: the 429/529 retry
  path and the 401/422 fail-fast path are asserted on the **observed request count**, not just the outcome, so a
  silently-dropped or silently-repeated call is a failure. The circuit breaker is disabled per-method in
  `JevWireMockResource` because each retry attempt counts against it and deliberate failures would otherwise leak
  across tests.
- **Eval suite** (`evals/`, plan §10/§13) — nine cases in `src/test/resources/evals/`, each a recorded diff plus
  the PR/author facts and the `jev` answers to replay. `EvalRunner` stubs only `GhClient` and `JevService` and
  then runs the real `ReviewEngine.review(...)`, so facts → triage → fan-out → combine is exercised end to end.
  Assertions are on the structured `ReviewResult` — severity, concern, message key — never on rendered prose, so
  model wording drift can't break the suite. Add a case by dropping `<name>.json` (+ `<name>.diff`) in that
  directory and listing it in `cases.txt`.
- **False-positive rate** (`FalsePositiveRateTest`) — the `clean: true` cases must come back completely silent;
  any finding on one is a false positive by construction. The budget is currently zero. See the caveat under
  *Implementation status*: this guards the silence default against regression, it does not calibrate it.

## Implementation status

Implemented end-to-end: facts layer, triage + gate + rule engine, all seven deep pipelines, interpretation
combine, orchestration (single + range), Opus report layer, the picocli CLI, the dependency health probe, and
the eval harness.

Known gaps / deliberate stubs (from plan §12 Phase 6 and §15):
- **The confidence bands are not calibrated.** The §6 bands and the I-6 emission thresholds in
  `thresholds.yaml` still ship at the plan's initial estimates. The eval corpus and `FalsePositiveRateTest` are
  hand-authored — they catch a regression in the gate or the rulesets, but they are fixtures written against the
  code's own model of itself, so a green run says nothing about the agent's real silence/emit rate. Fitting the
  bands needs a corpus of real `quarkusio/quarkus` PRs with human labels. **Until that exists, treat the
  severity of any individual finding as unproven.**
- **Compaction loss is not visible in the report.** When a diff exceeds the `jev` state budget, `DiffCompactor`
  labels the result lossy, names the files it summarized away, and logs a WARN — but none of that reaches the
  markdown a reviewer reads, so there is no way to tell from the output that the agent only saw part of the diff.
- **Native build not yet exercised** (plan §12 Phase 6).
- **The health check is a CLI subcommand, not a MicroProfile `@Readiness` check.** This app has no HTTP server,
  so a `@Readiness` bean would never be exposed; `pr-review health` is the equivalent. A deliberate departure
  from the plan's wording.
- **Pipeline B's `existing_test_covers_change`** is a noul ("an existing untouched test plausibly covers
  this") rather than a cite-the-path choice, because `jev`'s static question JSON can't enumerate dynamic
  test paths.
- **Salami-slicing across PRs** is a v1 limitation (the persistent "PR wall" is future work).

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/quarkus-pr-wall-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Related Guides

- SmallRye Fault Tolerance ([guide](https://quarkus.io/guides/smallrye-fault-tolerance)): Build fault-tolerant network services
- Picocli ([guide](https://quarkus.io/guides/picocli)): Develop command line applications with Picocli
- YAML Configuration ([guide](https://quarkus.io/guides/config-yaml)): Use YAML to configure your Quarkus application
