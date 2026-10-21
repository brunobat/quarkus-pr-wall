# Breaking-change confirmation pass

Deep pipeline C (`BreakingChangePipeline`) is supposed to be a *confirmation* pass over triage's
breaking-change verdict. It isn't confirming anything — it re-asks the same question with less evidence,
while being shown triage's answer.

What's actually in the tree today:

- C's `jev` state is `runtime_files` + `base_branch` + `triage_prior` + `untrusted`. Triage's state is
  the same hunk-level file facts for **every** file, plus the raw diff text, plus test bodies, plus
  `author_reference`. C's evidence is a strict subset of triage's.
- The one thing C has that triage doesn't is `triage_prior` — triage's own `breaks_callers` and
  `break_kind`, band and all, handed to the model as a prior to "confirm or overturn".
- C's three questions (`diff_breaks_callers`, `diff_break_kind`, `diff_has_migration_note`) are near
  duplicates of triage's, down to the "judge from the diff only" wording.

So a second, cheaper look is being anchored on the first look's answer. A model shown a CONFIRM-band prior
and less evidence than produced it has no basis to overturn it, and every reason to agree. Agreement then
reads as independent corroboration in the report, which is exactly the claim the design makes for C and the
only claim that justifies the extra `jev` call.

What I want out of this: decide what C is *for*, and make the code match that decision. Three directions
have been on the table and none has been chosen:

1. Give C a real evidence upgrade — something triage genuinely did not see — so the second look can
   disagree with the first.
2. Drop `triage_prior` so C is an independent second opinion rather than an anchored one.
3. Delete C's duplicate questions and stop pretending it's a confirmation pass.

Related, possibly mooted by whichever direction wins: the `diff_` prefix on C's three question ids
distinguishes nothing (triage's questions also say "judge from the diff only"). `confirm_*` was proposed
and never applied.

Constraints that aren't negotiable: the anti-gaming invariants I-1…I-6, C's independence from the other
deep pipelines (C never feeds G, G never feeds C), and the silence-by-default posture.
