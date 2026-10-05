# Remediation verification tooling retrospective

record_kind: TOOLING_RETROSPECTIVE
record_status: FINAL
correctness_state_changed: NO
repository_wide_clean_changed: NO

clean_source_sha: adf2f347ce9e20ec9f9376cf94053694353c9961
published_baseline_marker: db29f63ce169176b4c8ade4cec01f66cc0307ec8
review_basis_at_recording: 59b9ee498e020e1314aa51951dba64be97dda7a9

tool_blobs_at_baseline:
- tools/remediation/Invoke-Verification.ps1: 8f5c58a6a88b165ba8e8aadf32c420930cdddb10
- tools/remediation/Complete-Wave.ps1: e336d2a39fb4bf95eee9a8f689238ca49dfc2828
- tools/remediation/Test-ExecutionLifetimeProvenance.ps1: f1a564704d2b0faf5f4f27de0f7c1283dfcf42e0
- tools/remediation/README.md: c0070f6e9d19ef570d32cd4207af33ae30663cc8

## Scope

This retrospective evaluates the project-created remediation verification tooling, principally:
- Invoke-Verification.ps1
- Complete-Wave.ps1
- Test-ExecutionLifetimeProvenance.ps1
- their JSON/sidecar/evidence model

It does not evaluate RTK.

The source basis is:
1. the final baseline tooling source at the published Known-Good marker;
2. the full remediation session record supplied for retrospective review.

The transcript search contained 69 lexical occurrences of Invoke-Verification.ps1, 31 of Complete-Wave.ps1, 71 of Test-ExecutionLifetimeProvenance.ps1, 278 of verification.json, and 13 of "launch sidecar". These are text-occurrence counts, not invocation counts, but they establish that the tooling was used repeatedly across the campaign rather than being a one-off helper.

## Overall verdict

The tooling was materially useful and was used in the right role.

It successfully turned test/build execution into attributable evidence bound to:
- an exact committed candidate SHA/tree;
- a detached verification materialization;
- explicit test/gate identity;
- device/emulator identity where applicable;
- raw stdout/stderr retained separately from machine-readable summaries;
- nonzero execution and pass/fail/skip accounting;
- first-failure preservation;
- retry provenance for authorized infrastructure recovery;
- Gradle launch-environment provenance;
- completion-time provenance validation.

This was a major reason the campaign could distinguish:
- semantic test failures;
- harness defects;
- infrastructure failures;
- zero-test events;
- invalid/stale evidence;
- exact-final-SHA closure evidence.

The tooling should be retained. The principal opportunities are efficiency and evidence ergonomics, not a redesign of its trust model.

## What worked well

### 1. Exact-source binding was strong

Invoke-Verification creates a unique ignored evidence directory and detached Git worktree for the exact candidate. Evidence records bind to committed SHA/tree and the verifier re-checks source state throughout execution.

This prevented a common failure mode in long remediation campaigns: treating a passing result from an uncommitted or later-modified tree as evidence for a different published SHA.

Complete-Wave independently validates the execution-lifetime provenance instead of trusting only a top-level PASS field.

### 2. Evidence attempts were append-only in practice

A new verification invocation receives a new run directory. Recovery retry logic requires prior infrastructure evidence and creates a separate new run rather than overwriting the first failed attempt.

This matched the project's first-failure/evidence-preservation policy well.

### 3. Infrastructure and semantic failure were separated

The verifier modeled device-health, zero-test, stall, process, JUnit and execution-lifetime information separately enough that infrastructure events did not automatically become production findings.

This was especially valuable for:
- connected-test health failures;
- Gradle/tooling failures;
- emulator storage failures;
- runtime tests that never actually executed.

### 4. The tooling itself was treated as a correctness surface

BUG-TOOLING-01 was not waved away as "just harness code".

The Gradle launch sidecar initially conflated the outer wrapper distribution identity (for example gradle-8.13-bin) with the inner extracted root (gradle-8.13). The campaign classified that as a same-root tooling defect, added deterministic bin/all coverage, and required a real launch proof.

That is the correct model: evidence tooling that can misstate environment identity must itself have acceptance coverage.

### 5. Gradle launch provenance became materially stronger

The final tool distinguishes:
- distribution URL;
- distribution filename/name;
- Gradle version;
- extracted root name;
- wrapper bucket token/path;
- expected launcher artifacts;
- completeness state.

It also performs the observation read-only.

This corrected the earlier weak assumption that an outer directory name or .ok marker alone was sufficient evidence of a complete usable wrapper distribution.

### 6. Complete-Wave provided an independent aggregate gate

Complete-Wave validates evidence under a separate completion step and checks exact-source execution lifetime, candidate identity and evidence placement.

That separation is valuable: the runner creates evidence, while the completion tool decides whether the evidence set is acceptable for the requested wave.

### 7. The final campaign used the tool model successfully

By the end of the campaign, exact-SHA verification could support:
- full JVM execution;
- compile/build/artifact gates;
- complete instrumentation classes;
- first-failure preservation;
- one bounded infrastructure retry;
- exact published-SHA closure.

The tooling therefore achieved its primary goal.

## Inefficiencies observed

### 1. Build/materialization/install reuse was not explicit enough

Later union execution improved by reusing the same detached materialization and cached Kotlin/Java compilation across multiple classes.

Earlier focused waves more often rebuilt or re-established similar prerequisites around each gate.

The correctness rule that a behavior-relevant source/test change invalidates prior execution evidence is sound and must remain.

The inefficiency is narrower:
within the same exact SHA/tree and same tool/environment contract, reuse eligibility is still partly orchestrator judgment rather than a first-class machine-readable decision.

### 2. Emulator storage preflight became a workflow concern too late

Repeated INSTALL_FAILED_INSUFFICIENT_STORAGE events caused separate stop/review/authorization cycles.

Eventually the workflow added:
- /data free-space inspection;
- test-package/staging attribution;
- bounded cleanup;
- before/after reclamation accounting;
- exactly one install retry.

That policy should be available as a standard verifier capability rather than being rediscovered per finding.

The History-05 run ultimately reclaimed 273.2 MiB and succeeded on the single authorized retry, demonstrating that storage state was a real recurring harness concern rather than a one-off anomaly.

### 3. Evidence volume became expensive to inspect

The campaign intentionally preserved thousands of records. That is appropriate for auditability, but manual reasoning increasingly depended on sealed manifests and summary files because raw evidence volume was too large to inspect repeatedly.

The problem is not "too much evidence"; it is lack of a stronger compact authoritative index over that evidence.

### 4. Reuse invalidation could be more mechanical

The workflow often had to reason verbally about whether earlier JVM/compile/artifact results remained reusable after a stop.

The tool already records much of the needed identity data, but it could expose an explicit reuse verdict keyed to:
- candidate SHA/tree;
- tooling blob/version;
- exact requested gate arguments;
- built artifact hashes;
- API/ABI/device identity where runtime-specific;
- relevant package/install identity;
- whether behavior-relevant files changed after the recorded result.

### 5. Same-APK multi-class execution can be cheaper

The final-heavy wave ran four complete instrumentation classes against one exact final basis.

Where the same app/test APK pair and device state are valid for several classes, the preferred orchestration should build/install once and execute the classes serially without reinstalling between them.

The tool already accepts multiple connected-test classes in its documented invocation shape, so this is primarily a caller/orchestration policy improvement rather than a new semantic capability.

### 6. Tool acceptance coverage grew reactively

The eventual acceptance surface became strong, but some invariants were added only after failures exposed them:
- outer distribution versus inner extracted root;
- finalization serialization failure behavior;
- exact execution-lifetime provenance;
- path-budget behavior.

For future tooling changes, schema/invariant review before implementation should explicitly enumerate derived identities and distinguish:
"carrier identity", "derived expected identity", and "observed identity".

## Recommended improvements

These recommendations are tooling-only future work. They do not reopen the CLEAN baseline and are not required for the completed remediation campaign.

### Priority A — machine-readable evidence reuse contract

Add a compact artifact such as:

verification-reuse.json

It should state whether a prior result is reusable for a requested next gate and why.

Suggested key material:
- candidate SHA/tree;
- verification-tool version/blob;
- full normalized gate arguments;
- compile/test task set;
- app and androidTest artifact hashes;
- source/test/config cleanliness;
- API/ABI for runtime evidence;
- target/instrumentation package identity;
- device identity only where the result semantically depends on that device;
- prior result status.

The tool should return one of:
- REUSABLE;
- STALE_SOURCE;
- STALE_TOOL;
- STALE_ARTIFACT;
- STALE_RUNTIME_ENVIRONMENT;
- INCOMPATIBLE_GATE;
- NOT_REUSABLE_FAILURE_EVIDENCE.

Failure evidence itself remains preserved even when not reusable as PASS evidence.

### Priority A — standard storage preflight

Add an opt-in connected-test storage preflight that records, before install:
- /data total/used/free;
- installed debug/test package identities;
- package/install staging footprint where observable.

Do not make destructive cleanup automatic.

A separately explicit recovery switch should require:
- prior insufficient-storage evidence;
- caller authorization text;
- exact package identities;
- bounded test-owned cleanup only;
- before/after reclaimed bytes;
- one retry maximum.

This preserves the safety model while eliminating ad-hoc storage diagnostics from finding-specific prompts.

### Priority A — wave-level build/install reuse

For several connected classes on one exact candidate:
1. build once;
2. hash artifacts;
3. install once;
4. execute requested classes serially;
5. perform bounded health checks between classes;
6. emit per-class records plus one common artifact/install record.

A changed source/test/config SHA, artifact hash, package identity or material device reset invalidates reuse.

### Priority B — compact evidence index

Emit a stable wave-index.json containing:
- run IDs;
- gate IDs;
- status;
- exact SHA/tree;
- execution counts;
- artifact hashes;
- first failure;
- retry lineage;
- paths and hashes of detailed evidence;
- final acceptance disposition.

This lets reviewers verify the compact index first and open raw logs only for a disputed gate.

Raw logs and existing JSON remain append-only and authoritative evidence.

### Priority B — explicit derived/observed identity schema

Where the tool reasons about environment layout, encode separately:
- configured identity;
- derived expected identity;
- observed identity;
- comparison result.

Do not reuse one string as both configuration identity and observed filesystem identity.

The Gradle outer-distribution/inner-root bug is the model example for this rule.

### Priority B — automatic reuse explanation

When the wrapper decides to rebuild, reinstall or rerun, record a short reason field such as:
- artifact_missing;
- candidate_changed;
- test_apk_changed;
- device_package_mismatch;
- prior_gate_failed;
- explicit_forced_reexecution.

This makes expensive work auditable and reveals accidental redundant execution.

## What should not be optimized away

The following costs were justified and should remain:

- exact-final-SHA runtime closure;
- new evidence after a behavior-relevant source/test/config change;
- first-failure preservation;
- independent source-semantic review;
- fresh destination/ref check before publication;
- no unchanged semantic rerun merely to seek green;
- preserving failed infrastructure attempts before authorized retry;
- Complete-Wave rejection of incomplete/stale provenance;
- separate tooling acceptance for evidence-producing code.

These are trust boundaries, not inefficiencies.

## Non-tool workflow inefficiency observed

The session also contained substantial agent polling/wait traffic and repeated governance/bootstrap reading.

Those are orchestration issues outside Invoke-Verification/Complete-Wave themselves.

They should not be "fixed" by weakening verification tooling.

The later compact execution-capsule and bounded-bootstrap rules are the appropriate direction for that separate problem.

## Recommended future change shape

If the tooling is improved later, use a separate tooling-only wave.

Suggested order:
1. add reuse/index schema without changing existing acceptance;
2. add deterministic acceptance for reuse invalidation;
3. add read-only storage preflight;
4. add explicitly authorized bounded storage recovery;
5. add wave-level one-build/one-install multi-class execution;
6. verify backward compatibility with existing Complete-Wave evidence;
7. do not modify Android production behavior.

No such change is required to preserve the current Known-Good baseline.

## Final assessment

Tooling effectiveness: HIGH.

Trust model: KEEP.

Primary optimization target:
make reuse, storage preflight, and evidence indexing more machine-driven.

Primary anti-goal:
do not trade evidence integrity or exact-SHA attribution for shorter runtime.

The campaign demonstrated that the tooling was worth building. The next iteration should focus on making already-safe repeated work cheaper, not on weakening the gates.
