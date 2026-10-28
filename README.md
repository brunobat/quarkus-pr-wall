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
| Proportionality | triage | Does the PR make a reviewer read more than the change warrants? This is about volume, never about who or what wrote it. | planned |
| Per-file test adequacy | deep analysis | For each changed file: is it exercised and asserted by the PR's tests? This points at the files that need tests. | planned |
| Per-file breaking change | deep analysis | Which changed files carry the break, and which callers are affected | planned |

The planned list comes from the earlier, larger prototype (branch `poc-full-jev-2`, kept read-only for
reference). Its classifications are being rebuilt **one at a time** on this simpler base. Each one adds its
questions to the same single `jev` call per PR, and each lands only after a live run on real PRs shows its
answers are sensible. Test adequacy took three iterations (one choice question, then four questions, then a
project context and one option per reason) before its answers held up. Expect the same for the others.

**Facts are not classifications.** Some inputs are attested facts rather than model answers: who opened the
PR, whether the account is a bot, whether the author is a committer, and whether AI assistance was declared.
They may appear in a report, but no decision may ever read them. In particular, declaring AI assistance must
never count against a PR.

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
- **Large PRs:** a PR whose submission exceeds `JEV_MAX_SUBMISSION_CHARS` (60,000 characters by default) is
  not classified. It is noted and the run continues.
- **Accuracy:** no accuracy has been measured. Answers have been checked by hand on a handful of live PRs
  only, and there is no evaluation set yet.
- **Other repositories:** the project context describes `quarkusio/quarkus` only.
- **Error handling:** some lower-severity code-review findings are known and not yet fixed. For example, an
  empty `TYPESAFE_API_KEY` or a bad `JEV_PROJECT_CONTEXT` gives a long startup error instead of a one-line
  message.

## What it does today

A **read-only** command-line tool that classifies Quarkus pull requests for **test adequacy**. It is the triage
stage: for each PR it asks TypeSafe `jev` four PR-level questions in a single submission and reports `jev`'s
probabilities **exactly as returned**. Claude then writes a short prose summary of those answers.

The tool **classifies; it does not decide.** There are no thresholds, no verdicts and no severities. No
probability is compared against a number, and no answer changes the exit code. The tool never posts, approves,
labels or merges anything: the only `gh` commands it runs are `gh pr view` and `gh pr diff`.

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
| `tests-changed` | yes/no | Does this PR add or modify tests (files under a `src/test/` directory)? | |
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
PR is done. Every log line goes to stderr.

```
PR #57088 - test adequacy
  behaviour 0.96  tests-changed 0.11  reach: no-test-changes 0.96  assert: no-test-changes 0.91
Summary (Claude): jev judged it very likely that this PR changes runtime behaviour and unlikely that it adds or modifies tests, so for both reach and assert it chose no-test-changes.
Report: reports/review-57088.md

PR #57715 - test adequacy
  not classified: jev: {"error": {"type": "max_tokens_exceeded", ...}}
Report: reports/review-57715.md
```

- Probabilities are rounded to 2 decimals in the terminal, for display only.
- `Summary (fallback template): …` replaces the Claude summary when there is no key, the call fails, it takes
  longer than `ANTHROPIC_TIMEOUT`, or the text is blank. The template lists every answer, e.g.
  `jev classification: behaviour 0.96; tests-changed 0.11; reach no-test-changes 0.96; assert no-test-changes 0.91.`

**The report** (`review-<pr>.md`) has, in order:
1. the title, link and state;
2. **Classification**: each yes/no question with its text and probability, then each choice question with
   `jev`'s chosen option and every option's description and probability, all **unrounded**;
3. **Summary**: the prose and where it came from, `Claude (<model>)` or `fallback template`;
4. **Changed files**: each path and its change kind (context only);
5. **Run**: the `jev` model, the submission size and its limit, the project context used and its size,
   `jev`'s token usage, the summary source, and the
   Claude submission: the characters of the system and user messages sent (or "not sent") and Claude's reported
   token usage.

Text from GitHub, `jev` or Claude cannot change the report's structure: it never starts a line, stays on one
line, is escaped in tables, and cannot open HTML.

## Per-PR errors and exit codes

A PR that can't be classified is **noted and the run continues**. Its block shows `not classified: <reason>`
instead of the probabilities and summary, and its report has a **Not classified** section (the reason and the
full message) and the run details known by then. Claude is not called for it.

| Situation for one PR | Reason |
|---|---|
| `gh` fails (not found, not authenticated, timeout, diff too large for GitHub) | `gh: <message>` |
| The diff is empty | `empty diff` |
| The submission is over `JEV_MAX_SUBMISSION_CHARS` (checked before sending) | `submission <n> chars exceeds limit <limit>` |
| `jev` fails (HTTP error such as `max_tokens_exceeded`, timeout, invalid or incomplete answers) | `jev: <message>` |
| The report can't be written | the block ends with `Report: not written: <error>` |

Each of these counts as an error for that PR, and its full detail is logged to stderr.

| Run | Exit code |
|---|---|
| At least one PR without an error | 0, whatever `jev`'s answers are |
| Every PR had an error | 1 |
| `TYPESAFE_API_KEY` not set: stops before any PR, `error: … TYPESAFE_API_KEY` on stderr | 1 |
| `JEV_PROJECT_CONTEXT` unreadable or blank: stops at startup, before any PR, naming `JEV_PROJECT_CONTEXT` | 1 |
| Bad arguments (no PR, non-numeric PR, unknown option) | 2 |

## Configuration

Every value is set by an environment variable and declared once, in `ReviewConfig` (application settings) or
`application.yml` (extension settings).

| Variable | Purpose | Default |
|---|---|---|
| `GH_REPO` | `owner/repo` passed to every `gh` call | `quarkusio/quarkus` |
| `GH_BINARY` | the `gh` executable | `gh` |
| `GH_TIMEOUT` | how long one `gh` call may run before that PR is noted as not classified | `60s` |
| `JEV_MAX_SUBMISSION_CHARS` | the largest `jev` submission sent, in characters of the request body; a larger one is noted as not classified before sending, rather than being rejected by `jev` or sent partially | `60000` |
| `JEV_PROJECT_CONTEXT` | the project context sent at the start of every `jev` submission: a file path, or `classpath:<resource>` for a bundled one; it describes the repository's conventions, never an answer | `classpath:project-context/quarkusio-quarkus.txt` |
| `TYPESAFE_API_KEY` | `jev` API key | none (required) |
| `JEV_MODEL` | `jev` model name | `jev-latest` |
| `JEV_URL` | `jev` base URL | `https://api.typesafe.ai` |
| `JEV_TIMEOUT` | how long the `jev` call may take before that PR is noted as not classified | `60s` |
| `ANTHROPIC_API_KEY` | Claude API key | none (optional) |
| `ANTHROPIC_MODEL` | Claude model for the summary | `claude-opus-5-5` |
| `ANTHROPIC_TIMEOUT` | the longest the summary may delay a PR before the fallback template is used; the Claude call makes one attempt, with no retry | `60s` |

None of these settings interprets `jev`'s answers.

## Development

```shell
./mvnw test              # the whole suite: gh is a fake script, jev and Claude are WireMock stubs
./mvnw verify -Plive     # only tests tagged "live", which call the real GitHub, jev and Claude
```

Tests never reach a real service unless they are tagged `live`, and live tests run only with `-Plive`.

Layout (base package `io.quarkus.prreview`):

- `cli/`: the `review` command;
- `config/`: `ReviewConfig`;
- `clients/{gh,jev,claude}/`: the only code that talks to `gh`, `jev` and Claude;
- `pr/`: the PR model and diff parsing;
- `classification/`: the check contract, the single `jev` submission and the test-adequacy check;
- `triage/`: one PR from fetch to outcome;
- `report/`: terminal, markdown and summary rendering.

## Guides

- [Quarkus Picocli](https://quarkus.io/guides/picocli)
- [Quarkus configuration reference](https://quarkus.io/guides/config-reference)
- [Quarkus LangChain4j](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)
- [LangChain4j](https://docs.langchain4j.dev/)
- [GitHub CLI manual](https://cli.github.com/manual/)
