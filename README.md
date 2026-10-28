# quarkus-pr-wall

A **read-only** command-line tool that classifies Quarkus pull requests for **test adequacy**. It is the triage
stage: for each PR it asks TypeSafe `jev` a few PR-level questions in a single submission and reports `jev`'s
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

One submission per PR carries the PR's changed-file list and its unified diff (not its title or body), plus every
question:

| Label | Kind | Question |
|---|---|---|
| `behaviour` | yes/no | Does this PR change the runtime behaviour of the software? |
| `tests` | choice | How do the tests that this PR adds or modifies relate to the runtime behaviour it changes? `jev` picks one of `no-runtime-change`, `no-test-changes`, `tests-unrelated`, `tests-without-assertions`, `partially-exercised`, `multiple-gaps`, `fully-exercised`, `undetermined`, and gives a probability for each option. |

Every changed file is sent, test files included; no path rule filters or short-circuits anything.

## Output

stdout carries only the result, one block per PR with a blank line between blocks, each printed as soon as that
PR is done. Every log line goes to stderr.

```
PR #57720 - test adequacy
  behaviour 0.88  tests: no-test-changes 0.64
Summary (Claude): The change is very likely to alter runtime behaviour, and jev's most likely reading is that the PR adds or modifies no tests.
Report: reports/review-57720.md

PR #57715 - test adequacy
  not classified: jev: {"error": {"type": "max_tokens_exceeded", ...}}
Report: reports/review-57715.md
```

- Probabilities are rounded to 2 decimals in the terminal, for display only.
- `Summary (fallback template): …` replaces the Claude summary when there is no key, the call fails, it takes
  longer than `ANTHROPIC_TIMEOUT`, or the text is blank. The template lists every answer, e.g.
  `jev classification: behaviour 0.88; tests no-test-changes 0.64.`

**The report** (`review-<pr>.md`) has, in order:
1. the title, link and state;
2. **Classification**: each yes/no question with its text and probability, then each choice question with
   `jev`'s chosen option and every option's description and probability, all **unrounded**;
3. **Summary**: the prose and where it came from, `Claude (<model>)` or `fallback template`;
4. **Changed files**: each path and its change kind (context only);
5. **Run**: the `jev` model, the submission size and its limit, `jev`'s token usage, the summary source, and the
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
