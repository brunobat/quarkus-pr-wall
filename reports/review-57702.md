# PR #57702 — Fix Infinispan cache leaving stale in-flight computation on loader fa…

**Priority:** 2 (read-closely)  ·  **Blast radius:** 2 (extension-wide)  ·  **Blockers:** 0

## Findings

- **[NEEDS-ATTENTION]** A possible breaking change — confirm and document it. _(human confirm)_
  - _fix:_ Confirm whether this change breaks existing APIs or behavior, and if so document it in the migration guide and release notes.
  - _why:_ pipeline:breaking-change

## Pipelines

| concern | status | note |
|---|---|---|
| test-adequacy | OK |  |
| exempt-class | OK |  |
| breaking-change | OK |  |
| test-weakening | SKIPPED | no existing test touched |
| docs-missing | OK |  |
| domain-routing | OK |  |
| inconsistency | OK |  |

## Provenance

- head: `d4ee1b1b5f85792f2b1fa16772d00f34f7a2c74a`
- jev model: `jev-1.13.0`
- opus model: `claude-opus-5`
- triage questions: `triage-v3`
- ruleset: `triage-rules-v2`  ·  thresholds: `thresholds-v1`

