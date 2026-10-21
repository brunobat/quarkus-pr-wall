# Triage flow

How a PR gets from `gh` to a `TriageOutcome`, including the rules execution. Everything below is the
*current* implementation, not the plan.

A deep pipeline is named by the concern it owns, and that name is its only identity: `DeepPipeline.concern()`
returns it, the escalate rules in `questions/triage.json` name it in their `pipeline:` field, `ReviewEngine`
indexes by it, `FindingCombiner` looks it up with it, and the report shows it. The seven are `test-adequacy`,
`exempt-class`, `breaking-change`, `test-weakening`, `docs-missing`, `domain-routing` and `inconsistency`.

## End to end

```
  gh (read-only)                              resources/questions/triage.json
        │                                            14 questions
        ▼                                                  │
 ┌──────────────────────┐                                  │
 │ FactsService.gather  │  diff · file/test classification │
 │                      │  committer status · author hist. │
 └──────────┬───────────┘                                  │
            │ Facts                                        │
     ┌──────┴───────┐                                      │
     │              │                                      │
     ▼              ▼                                      │
┌─────────┐  ┌───────────────────────────────┐             │
│FactView │  │ TriageState.build()           │             │
│ (det.   │  │   change{ base_branch, flags, │             │
│  facts) │  │     files, diff ≤60k chars }  │◄─ DiffCompactor if oversized
│         │  │   tests{ bodies ≤40 lines }   │             │
│         │  │   author_reference{prior_areas}│            │
│         │  │   untrusted{ title, body }    │◄─ DATA, never instructions (I-4)
│         │  │   ─────────────────────────── │             │
│         │  │   EXCLUDED: committer status, │             │
│         │  │   account age  (I-2, I-3)     │             │
└────┬────┘  └──────────────┬────────────────┘             │
     │                      │ state                        │
     │                      └──────────┬───────────────────┘
     │                                 ▼
     │                   ╔═══════════════════════════╗
     │                   ║  jev  POST /v1/systemone  ║  ← the only network call
     │                   ╚═════════════╤═════════════╝     in the whole of triage
     │                                 │ JevResponse (raw noul / choice+conf / score+conf)
     │                                 ▼
     │                   ┌───────────────────────────┐
     │                   │ ConfidenceGate.band()     │   noul:  p≥.95 or p≤.05  → CONFIRM
     │                   │   per answer, asymmetric  │          .75≤p<.95, .05<p≤.25 → FLAG
     │                   │                           │          else → HUMAN
     │                   │                           │   choice/score: conf≥.90 CONFIRM
     │                   └─────────────┬─────────────┘                 conf≥.50 FLAG
     │                                 │ Map<id, BandedAnswer>
     │                                 │
     │                  basePriority = intrinsic_risk.scoreInt()
     │                                 │
     └──────────────┬──────────────────┘
                    ▼
   ┌────────────────────────────────────────────────────────────┐
   │  RuleEngine.evaluate()   — pure, no I/O, order-independent  │
   │  (detail below)                                             │
   └────────────────────────────┬───────────────────────────────┘
                                │ Decisions{ priority, findings,
                                │            escalations, silenced }
                                ▼
   ┌────────────────────────────────────────────────────────────┐
   │ withLowConfidenceEscalation()          I-5: "raises, never prunes"
   │   breaking-change neither silenced nor emitted → escalate it
   │   test-adequacy   neither silenced nor emitted → escalate it
   ├────────────────────────────────────────────────────────────┤
   │ effectiveBlastRadius()                                     │
   │   blast_radius, floored to blastRadiusFloor when either    │
   │   change_kind or blast_radius is sub-CONFIRM               │
   └────────────────────────────┬───────────────────────────────┘
                                ▼
                      ┌──────────────────┐
                      │  TriageOutcome   │  answers · decisions · blastRadius
                      └────────┬─────────┘  questionSetVersion · tuning · raw
                               │
            ┌──────────────────┴────────────────────┐
            │ ReviewEngine: full depth or range?    │
            └──────┬─────────────────────────┬──────┘
         full depth│                         │range
  (single targeted │                         │(sweep)
            PR)    ▼                         ▼
      ALL SEVEN deep pipelines     only the escalation set
      regardless of the gate       (+ exempt-class whenever
                                    test-adequacy runs)
```

## Rules execution, zoomed in

```
  LOAD (once, cached)                      ┌─────────────────────────────────┐
  questions/triage.json                    │ RulesetLoader                   │
    ├─ "questions" ─────── ids ───────────▶│  • parse "rules" (STRICT: an    │
    └─ "rules" ───────────────────────────▶│    unknown key fails the load)  │
                                           │  • resolve valueRef → named     │
  thresholds.yaml ────────────────────────▶│    dial in thresholds.yaml      │
                                           │  • RulesetLint, against THIS    │
                                           │    file's own question ids      │
                                           └────────────────┬────────────────┘
                                                            │ errors ⇒ THROW
                                                            │ else TuningConfig
                                                            ▼
  EVALUATE   inputs: bandedAnswers · FactView · ruleset · thresholds · basePriority

    priority ← max(basePriority, securityFloor)        I-1  has_security_file ⇒ ≥2
                            │
                            ▼
    ┌─────────────── for each ENABLED rule, if when[] all match ────────────────┐
    │                                                                           │
    │   predicate reads EXACTLY ONE of:                                         │
    │     answer: <slug> + band / is / choice / scoreAtLeast / scoreAtMost      │
    │     fact:   <name> + equals                                               │
    │   (never another pipeline's output, never raw PR text)                    │
    │                                                                           │
    │   then: raisePriority ─▶ priority = max(priority, value)   monotonic      │
    │         floorPriority ─▶ priority = max(priority, value)   monotonic      │
    │         escalate     ─▶ escalations ∪= {pipeline}          additive       │
    │         emit         ─▶ most-severe-per-concern            I-6 raise-only │
    │                          (tie → smaller rule id)                          │
    │         silence      ─▶ candidate, ONLY if the rule has a                 │
    │                          CONFIRM-clean guard               I-5            │
    │                                                                           │
    │   ✗ no lowering verb exists in the effect language                        │
    └───────────────────────────────┬───────────────────────────────────────────┘
                                    ▼
    priority ← applyAnomaly(priority)   I-2  outside_prior_areas@CONFIRM-yes or
                                             brand_new_account ⇒ +1, capped at 2
                                    │
                                    ▼
    silenced ← silenceCandidates − emittedConcerns − escalations     I-6
                                    │                     silence is the weakest effect
                                    ▼
                      Decisions{ priority, findings, escalations, silenced }
```

## Why this shape

**Confluence** — `max` for priority, set-union for escalations, most-severe-per-concern for emits. File
order and rule order cannot change a safety-relevant output.

**Monotonic safety** — every verb available to a tunable rule can only raise or add, so no edit to the
`rules` block can erode I-1…I-6. The invariant tier lives in `InvariantRules`, compiled in, not in the
editable file; `RulesetLint` fails the build on an unknown reference, a disallowed verb, or an unguarded
`silence`, and the strict parse fails it on a misspelled key.

**Rules live with their questions** — `questions/triage.json` holds both, because a rule and the answer id
it reads are one reviewable unit: rename an id and every rule reading it breaks in the same diff. It also
lets lint check a rule against exactly the ids its own file declares, rather than against a hardcoded
"triage ships in v1" set. The file keeps two versions: `version` is wire-visible and bumps only when a
question's wording changes; `rulesVersion` bumps when the interpretation is re-tuned.

**The gate is a cost control, not a safety mechanism.** An `escalate` rule can only *add* pipelines, and
`withLowConfidenceEscalation` adds two more on uncertainty — but in full-depth mode all seven run anyway,
so none of it applies. Pruning only happens in range/sweep mode.
