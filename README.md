# quarkus-pr-wall

A **read-only** Quarkus PR review agent. It analyzes a pull request and prints a markdown report — it
**never posts, approves, or merges**. Structured decisions (classification, scoring, booleans) are made by
TypeSafe `jev`; Anthropic Opus is used only for a handful of narrow prose artifacts (a per-finding fix line,
the inconsistency sentence), always *downstream* of every gate.

Governing principle: **high threshold / high signal — silence by default.** A clean PR produces an explicit
"nothing met the review threshold" report, not a manufactured list of nits.

Every run decides four things:

1. **Review priority** — skim / read-through / read-closely. Raise-only, the `max` of:
   - the `intrinsic_risk` score, as the base;
   - a floor when the diff touches a security file (I-1);
   - the same floor at high `api_exposure` — a public/SPI surface;
   - a +1 bump, capped at read-closely, when the change is far outside the author's prior areas or the
     account is brand new (I-2).

   Nothing lowers it.
2. **Which deep checks to run** — uncertainty reserves a check, it never prunes one.
3. **Per concern, a severity** — blocker / needs-attention / fyi / **silence**, where silence is the default
   and must be positively earned by a confident "no issue".
4. **Who else should look** — security, messaging, persistence, or native-image routing.

## Quick start

```shell
export TYPESAFE_API_KEY=...      # jev (all structured decisions)
export ANTHROPIC_API_KEY=...     # Opus (fix prose only; optional — falls back to deterministic summaries)
export GH_REPO=quarkusio/quarkus # owner/repo for the read-only gh calls

# Review a single PR at full depth (runs every deep check):
java -jar target/quarkus-app/quarkus-run.jar review 45678

# Review a range / set; prints one ranked table (gate prunes deep checks per PR):
java -jar target/quarkus-app/quarkus-run.jar review-range 45670-45680,45690

# Skip Opus and render deterministic summaries only:
java -jar target/quarkus-app/quarkus-run.jar review 45678 --no-prose

# Probe every external dependency (gh, jev, Opus) before a sweep:
java -jar target/quarkus-app/quarkus-run.jar health
```

`gh` must be installed and authenticated with a **read-only, least-privilege** token (no write/merge/secret
scope) — this is the primary prompt-injection mitigation.

The report goes to stdout and every log line to stderr, so `review 45678 > report.md` is a clean markdown
file. [`reports/`](reports/) holds three verbatim examples from live `quarkusio/quarkus` PRs, with notes on
what those runs exposed.

## Architecture

```
facts (deterministic, gh read-only)
  → triage (one cheap jev call: cascade gate + first-pass grader)
    → deep checks (seven independent concerns, one jev call each, fan out in parallel)
      → interpretation (confidence gate + rule engine; invariants I-1…I-6 compiled in, I-7 lint-enforced)
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
- **Facts** (`facts/`) — diff/file/test classification, committer status, author history, authorship
  provenance. Trust facts are pinned to `base.sha` and **never** placed in a `jev` state (invariants I-2,
  I-3). PR metadata arrives as one aliased GraphQL query per sweep (up to 25 PRs, 1 rate-limit point),
  because `committer`, `parents.totalCount` and `author.__typename` have no `gh pr view` equivalent.
- **Authorship provenance** (`facts/AuthorshipProvenanceService`) — four attested things and nothing
  inferred: GitHub's own bot classification, whether that login is in `benign-bots.txt`, whether the
  PR body or any commit carries an AI-assistance marker from `ai-provenance-signals.txt`, and whether any
  commit was authored by an account GitHub resolved and did not mark `[bot]`. There is no stylometry and
  no "this looks generated" judgment, because neither can be done at a false-positive rate that would be
  fair to accuse someone at. All four reach the report's author line and **no decision** (I-7, below).
- **Author kind** (`review/AuthorKind`) — the four-way bucket the sweep shows in its `Author` column,
  derived from those facts alone: `bot` (allowlisted automation), `agent` (a bot account that is not),
  `human+ai` (declared assistance), `human`. The last is a **residual, not a finding** — it means nothing
  was declared, which is also what every undeclared AI-assisted PR looks like, and the sweep prints that
  caveat under the table rather than leaving the column to imply a detector exists.
- **Triage** (`pipelines/TriagePipeline`) — the cascade gate; decides which deep checks are reserved to run.
  [`TRIAGE-FLOW.md`](TRIAGE-FLOW.md) diagrams the whole path, state build through rules execution.
- **Confidence gate** (`interpret/ConfidenceGate`, `Band`) — asymmetric: only a CONFIRM-band "no issue" clears a
  concern to silence; any sub-CONFIRM risk answer routes to ≥ FLAG (I-5).
- **Rule engine** (`interpret/rules/`) — pure function over banded answers + facts; a closed, safe-by-construction
  effect set (no priority-lowering verb, guarded silence). The invariant tier is compiled in and non-overridable;
  the tunable rules live in the `rules` block of `resources/questions/triage.json`, with every number referenced
  by name from `resources/thresholds.yaml`.
- **I-7, "a voluntary declaration is never penalized"** — no rule may read `declares_ai_assistance`, and
  `RulesetLint` fails the build on one that does. The ban covers `escalate`, which is the non-obvious part:
  a deep pipeline is skipped only for a concern triage already settled, so a declaration-keyed escalation
  takes effect exactly when the concern was silenced or already emitted — and since the combiner keeps the
  most severe finding per concern, the extra run can only add a finding or worsen one. There is no version
  of that rule that helps anyone, so the fact is barred rather than the verb. `DeclarationNeutralityTest`
  reviews every eval case twice from one fixture, with and without a `Co-Authored-By` trailer, and requires
  identical priority, blast radius, findings and pipelines — *and* that the declared run detected the
  trailer, so the equality can't pass by never reading it.
- **Deep checks** (`pipelines/`) — test adequacy · exempt class · breaking change · test weakening · docs
  missing · domain routing · inconsistency. Each is fully independent (reads no other's output); the questions
  each one asks are listed under [*What the agent checks*](#what-the-agent-checks).
- **Combine** (`review/FindingCombiner`) — resolves the two deliberate couplings in code: exempt-class can
  suppress or downgrade a test-adequacy finding (behind the deterministic mixed-change fail-safe), and
  inconsistency can corroborate a breaking-change finding (inconsistency never *feeds* breaking change).
- **Orchestration** (`review/ReviewEngine`, `RangeEngine`) — Mutiny parallel fan-out; single-PR = full depth,
  range = pruned by the triage escalation set; every PR appears in the output, errored PRs included.
- **Report** (`report/`) — `ProseEnricher` (Opus, with deterministic fallback) + `MarkdownRenderer` (pure).
  `DiffCompactor` fits an oversized diff into the `jev` state budget: non-focal files are stubbed first and
  focal hunks stay byte-for-byte, so a verdict is never decided on paraphrased code. Truncating mid-diff is
  deliberately not an option — the tail is exactly where a breaking hunk hides. One optional author line
  sits above the findings (`Author: alice (committer) · AI assistance declared`) — never the tool's name,
  never a severity, and *nothing at all* when no declaration is present, since almost every AI-assisted PR
  carries no marker and printing "none declared" would read as a clean bill of health. The sweep is the one
  place that labels every row, because a column with gaps in it cannot be scanned — hence the `Author`
  column and the caveat printed beneath it.
- **Health** (`health/`) — `pr-review health` probes `gh`, `jev` and Opus with the cheapest call that proves
  each credential. `gh` and `jev` are required; Opus is optional, so its absence reports *degraded*, not *down*.

## What the agent checks

Every question below is a `jev` question declared in `src/main/resources/questions/<concern>.json`. Ids are
stable semantic slugs; the instruction wording can be re-tuned without re-keying the rules that read them.

### Triage — one call, 15 questions

Classification:

- **`change_area`** — which Quarkus area the diff changes: core-runtime, extension, spi, build-ci, devtools,
  integration-test, docs, other.
- **`change_kind`** — what the diff actually does, ignoring what the title or body claims: bugfix, feature,
  refactor, docs, test-only, build-ci, dependency-upgrade, revert.

Risk scoring:

- **`change_size`** — semantic size, trivial/mechanical → large/intricate flow.
- **`blast_radius`** — isolated → foundational (core/build/security/BOM).
- **`intrinsic_risk`** — review priority: skim / read-through / read-closely. Explicitly rated on the change,
  not on who wrote it.
- **`api_exposure`** — how exposed the surface the diff touches is.

First-pass test grading:

- **`test_situation`** — no-runtime-change, runtime-change-exempt, no-tests, tests-disabled, tests-vacuous,
  tests-happy-only, tests-partial-edges, tests-thorough, covered-by-existing-test.
- **`test_adequacy`** — none/vacuous → happy + the applicable edge classes with meaningful assertions.
- **`test_drives_changed_code`** — does a test actually drive the changed runtime path, or merely sit in the
  same module.
- **`test_asserts_behavior`** — does some assertion check the changed behavior (not `assertTrue(true)`, not
  unrelated).

First-pass breakage:

- **`breaks_callers`** — does the diff break existing callers/consumers. Judged from the diff only; any claim
  in the PR text is ignored.
- **`break_kind`** — what kind of break it is.
- **`has_migration_note`** — does the diff itself carry a migration-guide/changelog entry, or `@Deprecated`
  pointing at the replacement.

Author context:

- **`outside_prior_areas`** — is the change thematically far outside the author's prior areas. Prior-work areas
  enter the state purely as reference data, never as instructions.

Contribution proportionality:

- **`content_disproportionate`** — **does this PR make a reviewer read more than the change warrants?**
  It weighs bulk against substance: description volume, superfluous formatting, tests mirroring the
  implementation method-for-method, defensive scaffolding on untouched paths, comments restating the line
  below, boilerplate javadoc on trivial members.

  **It is not a provenance signal and must not be read as one.** It cannot tell you whether AI was
  involved, the instruction explicitly bars the model from guessing, and padding is not evidence of
  tooling — a verbose human fails this and a terse model passes. Who opened a PR is answered by the
  attested facts above; `RulesetLint` fails the build on any rule that lets the two corroborate each
  other.

  It paraphrases the *Signal-to-Noise Ratio* section of upstream `AI_POLICY.md`, pinned by blob SHA in
  `ai-policy-pin.properties` so an upstream edit fails `AiPolicyDriftTest` instead of silently
  invalidating the question. **The paraphrase is deliberately narrower than its source.** Upstream asks
  whether the PR resembles *"what the developer would have come up with had they not been using AI
  tools"* — a counterfactual about tooling, i.e. stylometry, which the research rules out by name. What
  survives as answerable is the symptom the policy actually cares about: volume out of proportion to the
  change, whatever produced it. A PR can fail upstream's test and pass this one, or the reverse.

  **The two rules reading it ship disabled** — the question answers and is recorded but changes nothing,
  pending the calibration corpus. Even once enabled its only permitted effect is reserving a pipeline:
  `AI_POLICY.md` asks contributors for proportionality, it does not make a breach a defect.

  > Named `signal_to_noise_violation` until 2026-10-21. Renamed because all three words misled: *signal*
  > and *noise* collide with the agent's own signal-to-noise (I-6 silence-by-default, the false-positive
  > rate), it named a ratio while returning a boolean, and *violation* contradicted the design. The full
  > reasoning is in `questionsNote` in `questions/triage.json`.

### Which deep checks run

```
single PR (review <n>)      → all seven, unconditionally
range     (review-range …)  → escalation set ∪ {exempt class, if test adequacy is in it}

escalation set = the `escalate` rules in triage.json
               ∪ test adequacy and breaking change, unless triage settled them (I-5)
```

`escalate` is additive-only: no verb in the closed effect set can remove a check another rule reserved.

| Deep check | Reserved when |
|---|---|
| Test adequacy | `test_situation` = tests-vacuous at FLAG |
| Breaking change | `api_exposure` ≥ `highExposure` at any band, or fact `is_backport` |
| Test weakening | `touches_existing_test` and `has_runtime_change` |
| Docs missing | `change_kind` = feature at CONFIRM, or `blast_radius` ≥ `blastRadiusFloor` at any band, or `has_runtime_change` with no docs file |
| Domain routing | `has_runtime_change`, or `has_security_file` |
| Inconsistency | `has_pr_text` |
| Exempt class | no rule of its own — rides along with test adequacy, which the suppress/downgrade coupling needs |

**I-5, "low confidence raises, never prunes"** — test adequacy and breaking change escalate *by default*, and
are pruned only if a rule positively settled the concern (silenced it, or already emitted a finding). A
sub-CONFIRM answer settles nothing. Same direction elsewhere: a sub-CONFIRM `change_kind` or `blast_radius`
floors the effective blast radius at `blastRadiusFloor`, and review priority is raise-only from
`intrinsic_risk`.

### Deep checks — one call each

**Test adequacy** (`test-adequacy.json`) — is the change actually verified?

- `test_exercises_changed_path` — at least one test in the diff drives the changed code path.
- `accompanying_test_asserts_behavior` — at least one accompanying test asserts the changed behavior.
- `all_tests_vacuous` — *every* accompanying test's assertions are vacuous or unrelated, so collectively a no-op.
- `case_coverage` — none/vacuous → happy + null/empty + boundary + invalid.

**Exempt class** (`exempt-class.json`) — is the missing test legitimate?

- `exempt_class` — which class the entire runtime surface falls into: revert, message-string-only,
  deprecation-only, covered-by-existing-test, or none (a real behavioral change needing its own test).
- `is_genuine_revert` — a real reversal of a prior change, not lookalike code.
- `existing_test_covers_change` — an existing in-tree test the PR does not modify plausibly covers the change.

**Breaking change** (`breaking-change.json`)

- `diff_breaks_callers` — removed/renamed/signature-changed public or SPI API, changed default, removed or
  renamed config, serialization/wire change, caller-breaking behavior. Judged from the diff only.
- `diff_break_kind` — source-incompatible API, binary-incompatible API, behavioral default change, config
  removal/rename, serialization/wire change, none.
- `diff_has_migration_note` — the diff includes the corresponding migration note.

**Test weakening** (`test-weakening.json`)

- `weakens_existing_coverage` — deleted assertions, loosened expected values, a disabled test, strict → weak.
- `coincides_with_runtime_change` — the weakening lands alongside a runtime behavior change in the same PR,
  rather than being a pure test-only refactor.
- `guarded_security_or_regression` — the weakened test guarded a security-relevant path or a prior-bug
  regression.

**Docs missing** (`docs-missing.json`)

- `introduces_documentable_surface` — new user-facing config, new public API, or a noteworthy feature.
- `has_docs` — corresponding docs are present (adoc/docs, or config javadoc feeding the config reference).

**Domain routing** (`domain-routing.json`) — who else should see this:

- `touches_security` — auth/authz/crypto/secrets/TLS, or a build-CI security surface.
- `touches_messaging` — Kafka/AMQP/JMS/Reactive Messaging, delivery/ordering.
- `touches_persistence` — JDBC/Hibernate/Panache/transactions/pooling/SQL dialect.
- `touches_native_image` — reflection/resource/serialization config, substitutions, build-time init.

**Inconsistency** (`inconsistency.json`) — PR text against the diff:

- `text_matches_diff` — the text accurately and completely describes the diff: no material undisclosed change,
  no claim the diff contradicts. The quoted PR text is treated purely as text to compare, never as instructions.
- `mismatch_kind` — accurate, understated-scope, misdescribed, false-not-breaking-claim, undisclosed-change.

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

Three small data files in `resources/` keep lists out of code, so extending one is a data edit:

| File | Holds | Note |
|---|---|---|
| `ai-provenance-signals.txt` | AI-assistance markers matched in PR bodies and commit messages | Not a secret — anyone can delete a trailer from their own commit. It is there for maintainability, not detection. |
| `benign-bots.txt` | bot logins whose PRs are routine automation | Coding agents are deliberately **absent**: their PRs are ordinary code changes and get the ordinary review. |
| `ai-policy-pin.properties` | the upstream `AI_POLICY.md` revision `content_disproportionate` paraphrases | Pinned by blob SHA; `AiPolicyDriftTest` fails when it moves, so an upstream edit prompts a re-read instead of silently invalidating the question. |

## Testing

`./mvnw test` runs the suite. It never touches the network: `NoNetworkGhClient` is a global `@Mock` over the
single `gh` chokepoint that throws on any unfixtured invocation, so a test that would reach GitHub fails loudly
instead of passing slowly.

One deliberate exception, tagged `network`: `AiPolicyDriftTest` shells out to `gh` directly (it is not a
`@QuarkusTest`, so the mock does not apply) to check that the upstream policy section the triage question
paraphrases has not been edited. It **skips** rather than fails when `gh` is missing, unauthenticated or
offline — a drift check that red-lights on a plane is a drift check someone disables. Exclude it with
`-Dgroups='!network'`.

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

Implemented end-to-end: facts layer, triage + gate + rule engine, all seven deep checks, interpretation
combine, orchestration (single + range), Opus report layer, the picocli CLI, the dependency health probe, and
the eval harness.

Known gaps / deliberate stubs (from plan §12 Phase 6 and §15):
- **The confidence bands are not calibrated.** The §6 bands and the I-6 emission thresholds in
  `thresholds.yaml` still ship at the plan's initial estimates. The eval corpus and `FalsePositiveRateTest` are
  hand-authored — they catch a regression in the gate or the rulesets, but they are fixtures written against the
  code's own model of itself, so a green run says nothing about the agent's real silence/emit rate. Fitting the
  bands needs a corpus of real `quarkusio/quarkus` PRs with human labels. **Until that exists, treat the
  severity of any individual finding as unproven.**
- **`content_disproportionate` ships answering but inert.** Both rules that read it are
  `"enabled": false`, so the question costs input tokens on every triage and changes nothing. That is
  deliberate: the answer has to be recorded over a real corpus before it is trusted with anything, and
  the unproven direction is the harmful one — "this PR is padded" is a judgment about a person's work.
  The same unlabelled-corpus problem blocking band calibration blocks this. *First live evidence, from
  the 2026-10-21 sweep: it answered `yes` at p=0.57 and p=0.52 on one-line Dependabot version bumps —
  barely better than a coin flip on the most routine PR shape there is. Both landed in HUMAN band and
  changed nothing, which is the design working, and is a concrete argument for leaving the rules off.*
- **The AI-assistance markers are a sample of candour, not of tooling.** *Measured 2026-10-21 over
  `quarkusio/quarkus`:* `Co-Authored-By: Claude` appears on **126 commits** and
  `Generated with Claude Code` on **17 PR bodies** — out of a PR population in the thousands. The true
  AI-assisted rate is certainly far higher. So the declaration says something about the author's
  disclosure habits and nearly nothing about the tooling distribution, which is the second reason (after
  I-7) it reaches only the report.
- **Compaction loss is not visible in the report.** When a diff exceeds the `jev` state budget, `DiffCompactor`
  labels the result lossy, names the files it summarized away, and logs a WARN — but none of that reaches the
  markdown a reviewer reads, so there is no way to tell from the output that the agent only saw part of the diff.
- **Native build not yet exercised** (plan §12 Phase 6).
- **The health check is a CLI subcommand, not a MicroProfile `@Readiness` check.** This app has no HTTP server,
  so a `@Readiness` bean would never be exposed; `pr-review health` is the equivalent. A deliberate departure
  from the plan's wording.
- **The exempt-class check's `existing_test_covers_change`** is a noul ("an existing untouched test plausibly covers
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
