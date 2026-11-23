# quarkus-pr-wall

> **Status: proof of concept, work in progress.** This is an incomplete experiment, not a supported tool.
> It implements **one** classification (test adequacy) for the **triage** stage, and it takes **no
> decisions** yet. The classifications and decisions listed below are the plan, not features. Expect the
> questions, the output and the code layout to change.

## Where this is going

The goal is to help Quarkus maintainers triage pull requests. It works in two layers that are kept apart on
purpose:

1. **Classifications.** TypeSafe `jev` answers questions about a PR and returns probabilities. The tool
   records them exactly as returned. This is the only layer that exists today.
2. **Decisions.** Rules that turn classifications into something a maintainer acts on, such as a review
   priority or "ask for tests". None exist yet. They will come only after enough classifications have been
   collected on real PRs to calibrate them against maintainers' judgement.

The tool stays **read-only** in both layers. A decision is a suggestion to a human. The tool never comments,
labels, approves or merges.

### Classifications

| Classification | Stage | What `jev` is asked | Status |
|---|---|---|---|
| **Test adequacy** | triage | Does the PR change runtime behaviour? Does it add or modify tests? How much of the changed code do those tests execute, and do they assert its outcome? | **done** (this PoC) |
| Change area | triage | Which part of Quarkus the diff changes: core runtime, extension, SPI, build/CI, dev tools, integration tests, docs | planned |
| Change kind | triage | What the diff actually does, whatever the title says: bug fix, feature, refactor, docs, test-only, build/CI, dependency upgrade, revert | planned |
| Change size | triage | Semantic size, from trivial or mechanical to large and intricate | planned |
| Blast radius | triage | From isolated to foundational (core, build, security, BOM) | planned |
| API exposure | triage | How public the touched surface is: internal, extension SPI, public API, configuration | planned |
| Breaking change | triage | Does the diff break existing callers? What kind of break? Does it carry a migration note or a deprecation? | planned |
| Test weakening | triage | Does the PR remove, disable or loosen existing tests or assertions? | planned |
| Missing docs | triage | Does a user-visible change (configuration, behaviour, API) come without documentation? | planned |
| Domain | triage | Which specialist areas it touches: security, messaging, persistence, native image | planned |
| Authorship provenance | triage | Not asked of `jev`: read from GitHub's facts and two curated lists only. Who is the PR author, and each other commit author, to the repository: `team` (a member of the org), `community` (work already in the repository), `first-time`, `bot`, or `other (<reason>)`? Is the PR `agent` (opened or committed by a bot that is not routine automation), `declared` (an AI-declaration marker in the PR body or a commit message), or `none declared`? `none declared` means nothing was declared, not that no AI was used: nothing inspects the code or the writing style. See [Facts from GitHub](#facts-from-github). | **done** |
| Proportionality | triage | Does the PR make a reviewer read more than the change warrants? This is about volume, never about who or what wrote it. | planned |
| Per-file test adequacy | deep analysis | For each changed file: is it exercised and asserted by the PR's tests? This points at the files that need tests. | planned |
| Per-file breaking change | deep analysis | Which changed files carry the break, and which callers are affected | planned |

The planned list comes from the earlier, larger prototype (branch `poc-full-jev-2`, kept read-only for
reference). Its classifications are being rebuilt **one at a time** on this simpler base. Each one adds its
questions to the same single `jev` call per PR, and each lands only after a live run on real PRs shows its
answers are sensible. Test adequacy took three iterations (one choice question, then four questions, then a
project context and one option per reason) before its answers held up. Expect the same for the others.

**Provenance is reported, never judged.** Authorship provenance, and facts such as whether the author is a
member of the org, come from GitHub's own data rather than from a model's guess. They may appear in a report, but no
decision may ever read them. In particular, declaring AI assistance must never count against a PR, and
proportionality must never be read as a sign of AI use.

### Decisions the classifications are meant to support

None of these is implemented. Each will be one explicit rule, declared in one place, with its thresholds
calibrated on collected data rather than guessed.

| Decision | What it tells a maintainer | Classifications it would read |
|---|---|---|
| **Review priority** | skim, read through, or read closely | change size, blast radius, API exposure, breaking change |
| **Ranking a list of PRs** | which open PRs to look at first | review priority across PRs |
| **Ask for tests** | runtime behaviour changes but the PR adds no tests, or its tests don't execute or assert the change | test adequacy, test weakening |
| **Ask for docs or a migration note** | a user-visible or breaking change lacks documentation or a migration path | missing docs, breaking change, API exposure |
| **Severity per concern** | blocker, needs attention, FYI, or nothing to say (the default) | each concern's classification and how confident `jev` is |
| **Who else should look** | route to security, messaging, persistence or native-image reviewers | domain, blast radius |
| **Run deep analysis** | spend the more expensive per-file analysis only on PRs triage flags | any triage classification |
| **Fast-track candidates** | low-risk PRs, such as docs-only changes or isolated dependency bumps | change kind, change size, blast radius, test adequacy |

### What this PoC does not do yet

- **Decisions:** no thresholds, no verdicts, no severities. A probability is shown, never compared with a
  number.
- **Large PRs:** a PR whose submission is estimated over `JEV_MAX_SUBMISSION_TOKENS` (60,000 tokens by default)
  is not classified. It is noted and the run continues. `jev` gives no token count before sending, so tokens are
  estimated from the request body at 3.0 characters per token, below every ratio seen on real submissions, and
  1,000 tokens are kept for the answer.
- **Accuracy:** no accuracy has been measured. Answers have been checked by hand on a handful of live PRs
  only, and there is no evaluation set yet.
- **Other repositories:** only a `quarkusio/quarkus` project context is bundled. Another repository needs its
  own, set with `JEV_PROJECT_CONTEXT`.

## What it does today

A **read-only** command-line tool that classifies Quarkus pull requests for **test adequacy**. It is the triage
stage: for each PR it asks TypeSafe `jev` four PR-level questions in a single submission and reports `jev`'s
probabilities **exactly as returned**. Claude then writes a short prose summary of those answers. Next to
`jev`'s answers it shows facts read from GitHub: the PR's size, the CI checks on its head commit, with the
failing tests quarkus-bot reported, and who wrote the PR (see [Facts from GitHub](#facts-from-github)).

The tool **classifies; it does not decide.** There are no thresholds, no verdicts and no severities. No
probability is compared against a number, and no answer changes the exit code. The tool never posts, approves,
labels or merges anything: the only `gh` commands it runs are `gh pr view`, `gh pr diff`, `gh pr list`, and
`gh api` GET requests to a PR, to a user's commits and to org membership.

## Prerequisites

- Java 21.
- [`gh`](https://cli.github.com/) installed and authenticated, ideally with a read-only token.
- `TYPESAFE_API_KEY`: the `jev` API key. Required: without it the run stops before any PR.
- `ANTHROPIC_API_KEY`: the Claude API key. Optional: without it every summary uses the fallback template.

## Build and run

```shell
./mvnw package
export TYPESAFE_API_KEY=...
export ANTHROPIC_API_KEY=...   # optional

java -jar target/quarkus-app/quarkus-run.jar review 57720
java -jar target/quarkus-app/quarkus-run.jar review 57720 55753 57715 --output-dir /tmp/reports
```

```
review <pr>... [--output-dir <dir>]
```

- `<pr>...` are one or more PR numbers in `GH_REPO`, processed in the order given. A number given twice is
  processed once, at its first position.
- `--output-dir` is where the `review-<pr>.md` reports go. It defaults to `reports/` in the current directory
  (config property `pr-review.output-dir`). Missing directories are created, and an existing report is
  overwritten.

## What `jev` is asked

One submission per PR carries a **project context**, then the PR's changed-file list and its unified diff (not its
title or body), plus every question.

**Project context.** Fixed prose that tells `jev` what the repository is (Quarkus, a Java framework built with
Maven) and its layout: where shipped runtime code, build-time code and dependency versions live, that tests are
under `src/test/`, and that `integration-tests/` and `test-framework/` hold test-only modules and test
infrastructure rather than tests. It is the same for every PR and names no answer. The bundled file is
[`project-context/quarkusio-quarkus.txt`](src/main/resources/project-context/quarkusio-quarkus.txt);
`JEV_PROJECT_CONTEXT` points at another one, for example when `GH_REPO` names a different repository. It counts
toward the submission size.

**Questions.** Each asks about one thing:

| Label | Kind | Question | Options |
|---|---|---|---|
| `behaviour` | yes/no | Does this PR change the runtime behaviour of the software? | |
| `tests-changed` | yes/no | Does this PR add or modify tests? | yes when it adds or modifies files under a `src/test/` directory |
| `reach` | choice | How much of the changed runtime code do the tests this PR adds or modifies execute? | `all`, `some`, `none`, `no-runtime-change`, `no-test-changes`, `undetermined` |
| `assert` | choice | Do the tests this PR adds or modifies assert the outcome of the changed code they execute? | `all`, `some`, `none`, `no-runtime-change`, `no-test-changes`, `no-changed-code-reached`, `undetermined` |

For a choice, `jev` picks one option and gives a probability for each. Every option is one situation: when a
question does not apply, its own option says why (`no-runtime-change`, `no-test-changes`,
`no-changed-code-reached`).

The four answers are independent and are **never combined** into a single label. `jev` may give a combination
that looks inconsistent, such as a low `tests-changed` with `reach: all`; it is shown as returned.

Every changed file is sent, test files included; no path rule filters or short-circuits anything.

## Output

stdout carries only the result, one block per PR with a blank line between blocks, each printed as soon as that
PR is done. Every log line goes to stderr. A failed `gh` or `jev` call logs one warning line naming the PR, what
could not be read and the message; its stack trace is logged only at debug level, which
`QUARKUS_LOG_CATEGORY__IO_QUARKUS_PRREVIEW__LEVEL=DEBUG` turns on.

```
PR #57088 - test adequacy
  behaviour 0.96  tests-changed 0.11  reach: no-test-changes 0.96  assert: no-test-changes 0.91
Summary (Claude): jev judged it very likely that this PR changes runtime behaviour and unlikely that it adds or modifies tests, so for both reach and assert it chose no-test-changes.
Size: 3 files changed, +40 -12
CI: all 67 passed, skipped or neutral
Author: some-member (team) · AI: none declared
Report: reports/review-57088.md

PR #57715 - test adequacy
  not classified: jev: {"error": {"type": "max_tokens_exceeded", ...}}
Size: 12 files changed, +310 -95
CI: 2 of 67 not passed
  Check                                        Result   Test                                                          Flaky  What failed
  JVM Tests - JDK 21 (Quarkus CI)              FAILURE  io.quarkus.vertx.http.filters.GracefulShutdownFilterTest.test  yes    Timeout waiting for response - java.lang.RuntimeException
  JVM Integration Tests - JDK 17 (Quarkus CI)  FAILURE                                                                        Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.4:test (default-test) on project quarkus-inte…
Author: newcomer (first-time) · AI: declared (commit 0123abc: "co-authored-by: claude") · Commits: some-member (team)
Report: reports/review-57715.md
```

- A draft PR's line carries its state, e.g. `PR #57720 - test adequacy · State: OPEN (draft)`; other PR lines
  are as above.
- Probabilities are rounded to 2 decimals in the terminal, for display only.
- `Summary (fallback template): …` replaces the Claude summary when there is no key, the call fails, it takes
  longer than `ANTHROPIC_TIMEOUT`, or the text is blank. The template lists every answer, e.g.
  `jev classification: behaviour 0.96; tests-changed 0.11; reach no-test-changes 0.96; assert no-test-changes 0.91.`

**The report** (`review-<pr>.md`) has, in order:
1. the title, link and state, e.g. `Link: <url> · State: OPEN · CI on commit ba9690a`: ` (draft)` follows a
   draft's state, and `CI on commit <first 7 characters of the SHA>` names the head commit the CI checks were read
   for. It is left out when CI could not be read, and is a line of its own when the PR's title and link could not be
   read;
2. **Size (GitHub)**, **CI checks (GitHub)** and **Provenance (GitHub)**: the same facts as the terminal, with each
   check linked to its run, each failing test linked to its Develocity history, the job's build scan when there is
   one, and the time GitHub was read for who wrote the PR;
3. **Classification**: each yes/no question with its text and probability, then each choice question with
   `jev`'s chosen option and every option's description and probability, all **unrounded**;
4. **Summary**: the prose and where it came from, `Claude (<model>)` or `fallback template`;
5. **Changed files**: each path and its change kind (context only);
6. **Run**: the `jev` model, the submission's estimated tokens plus the 1,000 kept for the answer, checked
   against the limit, and its size in characters, the project context used and its size,
   `jev`'s token usage, the summary source, and the
   Claude submission: the characters of the system and user messages sent (or "not sent") and Claude's reported
   token usage.

Text from GitHub, `jev` or Claude cannot change the report's structure: it never starts a line, stays on one
line, is escaped in tables, and cannot open HTML.

## Facts from GitHub

Three facts are read from GitHub for every PR, whatever `jev` answered. They are shown **as GitHub returned them**:
nothing is concluded from them, and they are **never sent to `jev` or Claude**.

- **Size:** changed files, additions and deletions, as GitHub counts them. It is read even when GitHub refuses
  the diff (more than 300 files).
- **CI checks:** every check on the PR's **head commit**: check runs (by name and workflow) and commit statuses.
  A check passed when its value is `SUCCESS`, `SKIPPED` or `NEUTRAL`; any other value, including one still
  running, counts as not passed. The line says `none` when the head commit has no checks, `all N passed, skipped
  or neutral`, or `N of M not passed` followed by a table:

  | Column | What it shows |
  |---|---|
  | Check | The check's name, with its workflow (or `status` for a commit status) |
  | Result | GitHub's value, unchanged |
  | Test | A failing test, or empty for a failure that is not a test (e.g. "Failed to execute goal ...") |
  | Flaky | `yes` when quarkus-bot lists the same test as flaky under the same job, otherwise `no` |
  | What failed | The error quarkus-bot reported; one line of at most 120 characters in the terminal |

  The rows come from **quarkus-bot's status comment for the check's workflow on the head commit**; comments for
  earlier commits are never used, and when there are several the latest counts. A check without such a comment
  gets one row saying `details unavailable`, or `no source for failure details` on a PR quarkus-bot never
  commented on. Flaky tests in jobs that passed are not shown.

- **Provenance:** who wrote the PR, on one line: `Author: <login> (<who>) · AI: <label>`, followed by
  `· Commits: <login> (<who>), ...` when commits were authored by someone else.

  | Who | When |
  |---|---|
  | `team` | the PR author's association is `MEMBER` or `OWNER`; a commit author is a member of the repository's org |
  | `community` | not a member, with work already in the repository (`CONTRIBUTOR`, or a commit reachable from the PR's base) |
  | `first-time` | not a member, with nothing in the repository yet (`FIRST_TIME_CONTRIBUTOR`, or no such commit) |
  | `bot` | a bot account (for commit authors: a login like `app/...` or `...[bot]`) |
  | `other (<reason>)` | a deleted account, `NONE`, a placeholder account, a lookup that failed (with its error), or a commit author past `PROVENANCE_MAX_PEOPLE` |

  An outside collaborator (`COLLABORATOR`) is never `team`; it is `community` or `first-time` by its commits.
  Only each commit's **git author** is labelled: co-authors named in `Co-authored-by:` trailers come from text the
  PR controls, so they are never labelled, even when GitHub matched them to an account.

  | AI | When |
  |---|---|
  | `agent` | the PR author or a commit author is a bot that is not in the automation-accounts list |
  | `declared` | a marker from the AI-signals list is in the PR body or a commit message (case-insensitive, whitespace runs collapsed); shows where and which |
  | `none declared` | otherwise: nothing was declared, which does not mean no AI was used |

  These labels are GitHub's view **when the run read it** (the report shows the time): an author's association
  changes as their PRs are merged. GitHub shows **private** org members as members only to a token that belongs to
  an org member; with another token a private member reads `community` or `first-time`. A PR author costs one
  `gh api` call, made while `jev` is asked; each other commit author up to two, cached by `gh` for
  `GH_CACHE_TTL`. A commit author is looked up once per run, whichever PRs they appear in, with two exceptions: a
  `first-time` author's commit history is read again for a PR on another base commit, since their first PR may have
  been merged in between, and a lookup that failed is tried again in the next PR. Labels follow the order PRs are
  given: someone found `community` stays `community` for the rest of the run.

  The two lists are plain text files kept **outside the repository**, one entry per line, `#` comments and blank
  lines ignored, case-insensitive. By default they are read from `resource-files/` in the current directory, which
  is git-ignored:

  ```
  resource-files/ai-provenance-signals.txt   # markers, e.g. "co-authored-by: claude", "generated with claude code"
  resource-files/automation-accounts.txt     # routine automation, e.g. "dependabot", "quarkus-bot"
  ```

  A missing list logs one warning and counts as empty: no PR is then `declared`, and every bot is an `agent`.

`unavailable` means GitHub could not be read for that fact (the reason is logged to stderr); `none` means there
was nothing to show. To spare time and API calls, the facts of all PRs in a run are read up front with
`gh pr list`, a few dozen PRs per call; a PR missing from those results, a failed call, or a list of exactly 100
checks, comments or commits (where gh cuts lists off) is read again on its own with `gh pr view`.

## Per-PR errors and exit codes

A PR that can't be classified is **noted and the run continues**. Its block shows `not classified: <reason>`
instead of the probabilities and summary, and its report has a **Not classified** section (the reason and the
full message) and the run details known by then. Claude is not called for it.

| Situation for one PR | Reason |
|---|---|
| `gh` fails (not authenticated, rate limited, timeout, network, diff too large for GitHub) | `gh: <message>` |
| The diff is empty | `empty diff` |
| The submission's estimated tokens plus 1,000 for the answer are over `JEV_MAX_SUBMISSION_TOKENS` (checked before sending) | `submission estimated at <e> tokens + 1000 for the answer exceeds limit <limit>` |
| `jev` fails (HTTP error such as `max_tokens_exceeded`, timeout, invalid or incomplete answers) | `jev: <message>` |
| The report can't be written | the block ends with `Report: not written: <error>` |

Each of these counts as an error for that PR. Each logs one warning line on stderr; the stack trace is logged only
at debug level.

A number that is **not a pull request** in `GH_REPO` (an issue number, or a number that does not exist) is told
apart by GitHub's HTTP 404 for `repos/<GH_REPO>/pulls/<n>`, read only after its metadata read failed. Its whole
block is one line, `PR #58521: not a pull request in quarkusio/quarkus`. Nothing else is looked up for it, no
report is written, and nothing is logged at the default level (at debug level, `gh`'s message and stack trace). It
counts as an error.

| Run | Exit code |
|---|---|
| At least one PR without an error | 0, whatever `jev`'s answers are |
| Every PR had an error | 1 |
| `TYPESAFE_API_KEY` not set or empty: stops before any PR, `error: … TYPESAFE_API_KEY` on stderr | 1 |
| `JEV_PROJECT_CONTEXT` unreadable or blank: stops before any PR, `error: JEV_PROJECT_CONTEXT: …` on stderr (`--help` still works) | 1 |
| Bad arguments (no PR, non-numeric PR, unknown option) | 2 |

## Configuration

Every value is set by an environment variable and declared once, in `ReviewConfig` (application settings) or
`application.yml` (extension settings).

| Variable | Purpose | Default |
|---|---|---|
| `GH_REPO` | `owner/repo` passed to every `gh` call; `github.com/owner/repo` also works, but any other host fails the provenance lookups | `quarkusio/quarkus` |
| `GH_BINARY` | the `gh` executable | `gh` |
| `GH_TIMEOUT` | how long one `gh` call may run before that PR is noted as not classified | `60s` |
| `GH_CACHE_TTL` | how long `gh api --cache` keeps a per-person answer (org membership, commits before a base); a PR's own author association is never cached; under one second, nothing is cached | `1h` |
| `PROVENANCE_AI_SIGNALS` | the AI-declaration markers: a file path, or `classpath:<resource>`; a missing file warns and counts as empty | `resource-files/ai-provenance-signals.txt` |
| `PROVENANCE_AUTOMATION_ACCOUNTS` | the bot logins that are routine automation, not coding agents: a file path, or `classpath:<resource>`; a missing file warns and counts as empty | `resource-files/automation-accounts.txt` |
| `PROVENANCE_MAX_PEOPLE` | the most commit authors looked up on GitHub for one PR, besides its author; the rest read `other (lookup limit)` | `10` |
| `JEV_MAX_SUBMISSION_TOKENS` | the largest `jev` submission sent, in tokens, including the 1,000 kept for the answer; tokens are estimated from the request body at 3.0 characters per token; a larger one is noted as not classified before sending, rather than being rejected by `jev` or sent partially | `60000` |
| `JEV_PROJECT_CONTEXT` | the project context sent at the start of every `jev` submission: a file path, or `classpath:<resource>` for a bundled one; it describes the repository's conventions, never an answer | `classpath:project-context/quarkusio-quarkus.txt` |
| `TYPESAFE_API_KEY` | `jev` API key | none (required) |
| `JEV_MODEL` | `jev` model name | `jev-latest` |
| `JEV_URL` | `jev` base URL | `https://api.typesafe.ai` |
| `JEV_TIMEOUT` | how long the `jev` call may take before that PR is noted as not classified | `60s` |
| `ANTHROPIC_API_KEY` | Claude API key | none (optional) |
| `ANTHROPIC_MODEL` | Claude model for the summary | `claude-opus-5-5` |
| `ANTHROPIC_TIMEOUT` | the longest the summary may delay a PR before the fallback template is used; the Claude call makes one attempt, with no retry | `60s` |

None of these settings interprets `jev`'s answers.

An exported but empty variable counts as unset. Each setting can also be set under its property name, e.g.
`PR_REVIEW_JEV_PROJECT_CONTEXT` or `QUARKUS_LANGCHAIN4J_TYPESAFE_API_KEY`. An empty API-key property means no key,
but an empty `PR_REVIEW_*` variable is not supported: it would hide the default and startup fails. Use the names
above.

The character limit `JEV_MAX_SUBMISSION_CHARS` was replaced by `JEV_MAX_SUBMISSION_TOKENS`, with no alias. An
exported `JEV_MAX_SUBMISSION_CHARS` is ignored. A leftover `pr-review.jev.max-submission-chars` property in a
property source makes startup fail, because it maps to no setting.

## Development

```shell
./mvnw test              # the whole suite: gh is a fake script, jev and Claude are WireMock stubs
./mvnw verify -Plive     # only tests tagged "live", which call the real GitHub, jev and Claude
```

Tests never reach a real service unless they are tagged `live`, and live tests run only with `-Plive`.

Layout (base package `io.quarkus.prreview`):

- `cli/`: the `review` command;
- `config/`: `ReviewConfig`, `ApiKey` and `BlankEnvironmentIsUnset`;
- `clients/{gh,jev,claude}/`: the only code that talks to `gh`, `jev` and Claude;
- `pr/`: the PR model and diff parsing;
- `classification/`: the check contract, the single `jev` submission and the test-adequacy check; `github/`, the
  facts read from GitHub (size, CI, and the bulk read); `provenance/`, who wrote a PR;
- `triage/`: one PR from fetch to outcome;
- `report/`: terminal, markdown and summary rendering.

## Guides

- [Quarkus Picocli](https://quarkus.io/guides/picocli)
- [Quarkus configuration reference](https://quarkus.io/guides/config-reference)
- [Quarkus LangChain4j](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)
- [LangChain4j](https://docs.langchain4j.dev/)
- [GitHub CLI manual](https://cli.github.com/manual/)

## LangChain4j limitations we worked around

This PoC runs on Quarkus `4.0.0.Beta1` with quarkus-langchain4j `2.0.0.Beta1` (LangChain4j `1.21.0`). These
are the gaps we hit and how the code works around each one. Each is worth revisiting on a newer release.

| Limitation | Workaround |
|---|---|
| The Decision API's `@Decide` decision services make one `jev` call per method, so several questions cost several calls. | Our own single-submission contract (`classification/api`, `JevSubmission`): every check adds its questions to one `DecisionRequest`, answered in one call per PR. |
| `DecisionRequest` has only `input`, `questions`, `parameters` and `modelName`. There is no system or context field. | The project context is placed at the start of the input text, and it counts toward the submission size. |
| There is no public way to know a submission's size before sending it. `jev` rejects an oversized one with a 400 `max_tokens_exceeded`, which arrives as an `InvalidRequestException` with `jev`'s body in the message. | `SubmissionSize` serializes the request with `langchain4j-typesafe`'s own internal mapper and codec settings. It depends on LangChain4j internals, and an end-to-end test checks that the size matches the body `jev` receives. `jev` limits tokens but offers no count before sending, so the size is turned into a token estimate at 3.0 characters per token, with 1,000 tokens kept for the answer. Oversized PRs are noted before anything is sent. |
| A choice answer's probabilities are a map, with no guarantee they come back in the order the options were declared. | `JevSubmission` reorders them to the declared order before they are recorded. |
| An AI service is request-scoped by default, and a picocli command has no request context, so the call fails. | `ClassificationSummarizer` is `@ApplicationScoped`, with no tools and no chat memory declared explicitly. |
| A plain `String` return type gives no token usage. | The AI service returns `Result<String>`, which carries Claude's token usage for the report. |
| The Anthropic extension accepts only `max-retries` of 1 or more. LangChain4j also retries an attempt that was interrupted by our timeout, so the call carries on in the background. | `NoRetries`, a `ModelBuilderCustomizer`, sets `maxRetries(0)` after the configuration is applied. A test proves exactly one request is made on a timeout or an HTTP 500. |
| There is no single cap on how long a Claude call may delay a PR. | `SummaryService` runs the call on a `ManagedExecutor` and waits at most `ANTHROPIC_TIMEOUT`, then cancels it. It uses `submit()`'s `Future`, because `CompletableFuture.cancel` does not interrupt the thread. |
| An empty API key, or the extension's own `dummy` placeholder, makes startup fail once an AI service exists, even for `--help`. | `application.yml` defaults both keys to `not-set`. The app treats that as "no key": the run stops with a one-line error for `jev`, and the summary falls back to the template for Claude. An exported but empty variable still reached the extension as an empty value, so `BlankEnvironmentIsUnset`, a SmallRye `ConfigSourceInterceptor`, makes a blank environment variable count as unset and `${VAR:default}` falls back to its default. It must sit below SmallRye's expression interceptor to see those lookups. An interceptor can't skip one source, so a blank property-form variable (`PR_REVIEW_*`) can't fall back to its default; only a blank API-key property is handled, by replacing it with `not-set`. |
