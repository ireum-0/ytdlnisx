# BUG-OBSERVE-02 — current-basis revalidation at ee7eea00

Date: 2026-09-24 +09:00

Exact independently CLEAN basis:
`ee7eea001462b77e88a201ed2f26c2385048d421`

Related current P0 checkpoint:
`8608e4abd1cf7cffab66feb96f9b97470a065f9a`

## Verdict

**OPEN / CONFIRMED / NOT_CLEAN — existing P2 `BUG-OBSERVE-02` remains valid at exact current basis `ee7eea00...`.**

Canonical count delta: 0.
Totals remain P0 1 / P1 0 / P2 23.

## Exact current evidence

The ordinary edit UI still constructs a new `ObserveSourcesItem` for an
existing source with `runCount = 0`.

Worker-owned fields omitted by that constructor retain their defaults, including
empty run history, false run-in-progress, and blank current status.

`ObserveSourcesViewModel.insertUpdate()` passes this reconstructed item to
`ObserveSourcesRepository.update()`.

`ObserveSourcesDao.update()` remains a full-row
`@Update(onConflict = REPLACE)`.

Therefore a configuration-only edit can durably reset worker-owned runtime
progress/state, independently of the P0 stale-worker sequence.

## Correction relation

Keep `BUG-OBSERVE-02` distinct in canonical identity/count from P0
`BUG-OBSERVE-HANDOFF-01`, but authorize one coherent Observe correction wave
because both require the same ownership split:

- configuration writes must mutate only configuration-owned fields and advance
  an exact durable configuration generation;
- worker runtime writes must mutate only runtime-owned fields and require the
  exact expected current generation/status;
- no full-row stale snapshot may cross either ownership boundary.

The correction must preserve explicit user-authorized processed-link reset
behavior as an intentional configuration action rather than accidentally
preserving/resetting all runtime state.

INDEPENDENT EXECUTION: NOT EXECUTED
