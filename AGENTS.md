# AGENTS.md -- Quarkus Project Instructions

This is a Quarkus application. Follow these rules when working on this project. The project rules come first;
the Quarkus sections after them are the generic template.

## Project rules -- quarkus-pr-wall (read these first)

This is a read-only picocli CLI that classifies Quarkus pull requests for test adequacy with TypeSafe `jev`, through
the LangChain4j Decision Models API, and has Claude write a prose summary. It is a work-in-progress PoC; the
README's top section lists the planned classifications and decisions.

### GitHub is read-only

- NEVER change anything on GitHub: no comments, labels, reviews, approvals, merges or issue edits.
- The only `gh` commands the code may run are `gh pr view` and `gh pr diff`. Don't add others.

### Classify, don't decide

- The app records `jev`'s probabilities exactly as returned. It never compares a probability with a number,
  never ranks or combines answers, and never turns them into a verdict, severity or exit code.
- Don't add thresholds, cut-offs or rules on top of the classifications unless the user explicitly asks for a
  decision. If a design choice would add one, flag it and ask.
- `NoDecisionLogicTest` guards this. It is a heuristic, so don't work around it.
- Operational limits (the submission size and the timeouts) are declared once, in `ReviewConfig` or
  `application.yml`, with their purpose stated. Never repeat them as literals in code.
- Authorship provenance, when it lands, is reported and never read by a decision.

### Asking `jev`

- There is one `jev` call per PR. Every check adds its questions to the single submission
  (`classification/api`, `JevSubmission`); don't use `@Decide` services.
- Questions are PR-level only. Per-file questions belong to the later deep-analysis stage.
- Each question asks about one thing, and each choice option is one situation. When a question doesn't apply,
  its own option says why (`no-runtime-change`, `no-test-changes`, ...). Never use a combined "not applicable".
- The input starts with the project context (`JEV_PROJECT_CONTEXT`, default
  `src/main/resources/project-context/quarkusio-quarkus.txt`), then the changed-file list and the diff. The PR
  title and body are never sent.
- `TestAdequacyCheckTest` pins the question wording. Changing what `jev` is asked is a deliberate change: update
  the spec, that test and the README's question table together, then confirm with a manual live run.

### Package layout

Base package `io.quarkus.prreview`:

- `clients/{gh,jev,claude}` holds the only code that talks to external systems. Business code goes through it,
  and clients return our model types.
- `classification/{api,model,submission,checks/<name>}`, `pr/`, `triage/` and `report/{terminal,markdown,summary}`
  hold the business logic.
- Place a class by what it is: results go in `model`, contracts in `api`, and anything that only presents a
  classification goes under `report/`. Don't make packages flat.
- Tests mirror the main packages. Test support (WireMock stubs, the fake `gh`) lives in `it/support`.

### Configuration and defaults

- Every option and setting has a sensible default. The command must run with only PR numbers, and at least one
  end-to-end test runs it with no optional flags.
- A new environment variable is declared once (`ReviewConfig` or `application.yml`) and added to the pom's
  `excludedEnvironmentVariables` list, together with its `PR_REVIEW_*` form, so a developer's shell can't leak
  into tests. It is also documented in the README's configuration table.
- An exported but empty environment variable counts as unset (`BlankEnvironmentIsUnset`).
- Setup errors (a missing key, an unreadable project context) are one line, `error: ...` on stderr, exit 1. They
  are checked after argument parsing, so `--help` still works. Bad arguments exit 2. Known exception: a
  non-positive `ANTHROPIC_TIMEOUT` with a Claude key set still fails at startup.

### Tests in this project

- This is a CLI, not a long-running app, so the Dev MCP test runner below doesn't apply. Run the suite with
  `./mvnw test`, and note in your summary that you did.
- To count tests, delete `target/surefire-reports` first. Stale reports from renamed tests otherwise inflate the
  count.
- Tests never reach a real service: `gh` is a fake script (`src/test/resources/fake-gh`), and `jev` and Claude
  are WireMock stubs or mocks. A test that calls a real GitHub, `jev` or Claude endpoint must be tagged
  `@Tag("live")`. It then runs only with `./mvnw verify -Plive`.
- For behaviour the stubs can't show (startup, environment variables, real `jev` answers), also check the packaged
  jar (`./mvnw package -DskipTests`, then `java -jar target/quarkus-app/quarkus-run.jar ...`). Live runs against
  real PRs are done manually by the user.

### Workflow

- Features go through the QRSPI workflow in `qrspi/<feature>/` (spec, plan, phases, reviews, `state.json`). The
  folder is git-ignored. Change the spec first, then the plan, then the code.
- Commit messages end with the `Co-Authored-By` line from the session instructions.
- Keep `README.md` current, including its "LangChain4j limitations we worked around" section when a new
  workaround is needed.

## CRITICAL -- Extension-First Rule (NEVER skip this)

**STOP before writing ANY code.** For every feature or capability the user requests:

1. **Search for Quarkus extensions** that provide the capability using `quarkus_searchDocs` and `quarkus_searchTools query='extension'`.
   Do NOT rely on a fixed list of extensions -- always search dynamically, as available extensions change across Quarkus versions and platform BOMs.
2. **Present ALL matching extensions to the user** with a recommended default marked. Wait for the user to choose before proceeding.
   Do NOT silently pick an extension when multiple options exist.
3. **Load skills** with `quarkus_skills` for the chosen extension BEFORE writing any code.

Skipping any of these steps is a violation. NEVER implement a feature by hand-coding HTML, JavaScript, REST endpoints, or other functionality when a Quarkus extension exists for it.

## Required Workflow

1. **Use quarkus_update (via a subagent if supported) when returning to this project** -- checks if the Quarkus version is up-to-date and suggests upgrades.
2. **Use quarkus_skills BEFORE writing any code or tests** -- it contains extension-specific patterns, testing approaches, and common pitfalls that prevent mistakes. Skills may also list **Available Dev MCP Tools** specific to each extension (e.g. OpenAPI schema retrieval, scheduler job management) -- use these via `quarkus_callTool`. Call this EVERY time you are about to add or modify a feature, not just at project creation.
3. **Use quarkus_searchDocs for Quarkus documentation** -- do NOT use generic documentation tools (Context7, web search). The Quarkus doc search is version-aware and more accurate.
4. **Use quarkus_searchTools to discover Dev MCP tools** on the running app for testing, config changes, and extension management. The tool list is **dynamic** -- it changes when extensions are added or removed. Re-call `quarkus_searchTools` after any extension change to discover newly available tools. Note: some extension-specific tools are also documented in the skills output (see step 2).
5. **Use quarkus_callTool to invoke Dev MCP tools** -- run tests, add extensions, update configuration. Do NOT run Maven/Gradle commands manually.
6. **After code changes, trigger a reload** via `quarkus_callTool` with toolName `devui-logstream_forceRestart`. Do NOT restart the app manually.
7. **After pom.xml / build.gradle changes** (adding dependencies or extensions), you MUST do a full `quarkus_stop` + `quarkus_start` cycle. A `forceRestart` only recompiles source files -- it does NOT re-resolve dependencies.

## Rules

- NEVER implement features manually when a Quarkus extension exists -- search for and add the right extension first.
- NEVER silently pick an extension when multiple options exist -- ALWAYS present options to the user and wait for their choice.
- NEVER write code for a feature without first loading its skill via `quarkus_skills`.
- ALWAYS write tests for every feature -- no exceptions.
- ALWAYS keep README.md updated with app description, features, endpoints, and Quarkus guide links.
- ALWAYS summarize after completing work -- when you finish building an app, adding a feature, or completing a task, provide a clear summary of what was done (files created/modified, endpoints added, extensions used, etc.) and suggest logical next steps the user might want to take (e.g. adding security, observability, persistence, testing improvements, deployment).
- Use `@QuarkusTest` for integration tests -- Dev Services auto-starts backing services (databases, messaging, etc.).
- Use `%dev.` and `%test.` profile prefixes for dev/test configuration -- never hardcode connection URLs without a profile prefix.

## Testing

> **In this project** the tests run with `./mvnw test`, not the Dev MCP runner described below; see
> "Tests in this project" above.

If your agent supports subagents, run tests in a **subagent** so the main conversation stays responsive:

```
If supported, use the Agent tool to launch a subagent with this prompt:
  "Run the Quarkus tests for project <projectDir> using quarkus_callTool
   with toolName 'devui-testing_runTests'. Analyze the results and report
   which tests passed, failed, or errored. If tests fail, include the
   failure messages and suggest fixes."
```

- Use `devui-testing_runTests` to run all tests.
- Use `devui-testing_runTest` with arguments `{"className":"com.example.MyTest"}` to run a specific test class.
- Do NOT run Maven/Gradle test commands manually -- the Dev MCP test tools handle compilation, hot reload, and result reporting.
- After fixing test failures, re-run tests (via a subagent if supported) to verify the fix.
- **NEVER run `mvn clean` or `gradle clean` while dev mode is running** -- it deletes `target/test-classes` and breaks the test runner with no automatic recovery.
- If the test runner gets stuck returning "Tests already in progress", do a full `quarkus_stop` + `quarkus_start` cycle to reset the test runner state.

## Error Handling

When something goes wrong (compilation error, deployment failure, runtime exception):

1. Use `quarkus_callTool` with toolName `devui-exceptions_getLastException` to get structured exception details (class, message, stack trace, user code location).
2. Fix the issue based on the exception details.
3. Call `devui-exceptions_clearLastException` to clear the recorded exception.
4. Use `quarkus_logs` only when you need broader log context beyond the exception itself.

**Note:** If the app fails on its very first deploy (before the Dev MCP handler is registered), the exception endpoint won't exist yet -- fall back to `quarkus_logs` in that case. For hot-reload failures (the common case), the endpoint is always available from the prior successful deploy.

## Customizing Skills

**Global customizations** (`~/.quarkus/skills/`) apply to all projects. Use `quarkus_updateSkill`
to create or update global customizations. Ask the user whether to ENHANCE (append to the base)
or OVERRIDE (fully replace the base).

**Project-level skills** (`.agent/skills/`) are standalone files readable by any agent.
Use `quarkus_saveSkill` to materialize the full composed skill into `.agent/skills/`,
then edit the file directly to customize it for the project.
