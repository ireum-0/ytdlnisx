# BUG-UPDATER-04 canonical liveness defect confirmed from persisted exact-source capsule

review_parent_sha: e01c32baa0ec749b172717f9a1613dc34c90c8ec
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
local_candidate_sha: 05c1fc2ed53531da6935f93470df93028bd799f3
local_candidate_tree: d675b3b1bb2aa4e8570e0113d525b9582c3c367a
local_candidate_parent: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Independent reviewer disposition:
PROVEN_LIVENESS_DEFECT
SAME_ROOT_RESIDUAL
CORRECTION_REQUIRED
CANDIDATE_NOT_PUBLISHABLE
NOT_FIXED
NOT_CLOSED
NOT_CLEAN

Authoritative persisted evidence:
private:ytdlnisx-review
path: ytdlnisx/evidence/2026-10-04__05c1fc2e__BUG-UPDATER-04_exact-source-liveness-capsule.md
evidence_commit: 8d71220134a55c94f7eaec523b8d4c520bb41714
evidence_blob: 0c5aebc74f9ef026f5475cf541d02775342b3bd1

Independent source confirmation:

1. Reachable production producer
- persisted data-fetch command templates can be inserted into generated config;
- normal format refresh reaches YTDLPUtil.getFormats and executeLibraryRequest without a supplied process ID;
- requestRequiresMutation classifies --update, --update-to, --alias and short update flags as mutation requests.

2. Wrong durable native identity class for anonymous generic mutator
- YoutubeDLCompat.executeWithQuiescence assigns an anonymous null-ID request:
  consumer:<UUID>
  before mutation classification;
- if requestRequiresMutation is true, the same consumer identity is passed unchanged into executeNativeWithQuiescence under YtdlpRuntimeAuthority.withMutation;
- YtdlpNativeProcessBarrier.prepare therefore durably publishes a consumer_-named marker carrying the exact generation token.

3. Mutation recovery selector mismatch
- YtdlpNativeProcessBarrier.recoverRuntimeMutationNativeDebt selects only marker filenames starting with mutation_ and requires processId starting with mutation:;
- the retained consumer_ marker is therefore excluded from exact native recovery.

4. Observation does not substitute for recovery
- runtimeMutationIsQuiescent scans all markers but only calls proveGenerationAbsent(token);
- proveGenerationAbsent is read-only and never signals or recovers a live generation;
- therefore a retained-live consumer generation remains visible as live but is not driven to quiescence.

5. Reader fence remains correctly fail-closed
- runtime publication debt remains non-QUIESCENT until exact native quiescence, runtime usability validation, and publication completion succeed;
- withConsumer refuses admission while mutation publication debt remains;
- its recovery loop re-enters the same mutation recovery path, which again excludes the consumer_-scoped generation.

6. Liveness defect
- later ordinary readers, new generic mutation requests, updater/startup recovery, runtime initialization, Download/Terminal/History recovery and diagnostics do not automatically discover and select the old anonymous consumer identity;
- manual/known-ID exact destruction capability exists, but no production automatic owner retains or rediscovers that anonymous identity;
- spontaneous native exit can eventually unblock the state, but source provides no guaranteed deadline or automatic retry owner for a retained-live generation;
- thus there exists a supported production state where safety fencing remains active but convergence is not guaranteed.

This is one semantic root under BUG-UPDATER-04, not a new P2. Canonical counts remain unchanged.

Historical focused failure:
- the earlier laterUpdaterProgressAfterExactMutationRecovery false result used a mutation:-scoped fixture and remains NOT_VERIFIED;
- do not alias that event to this newly proven consumer-identity liveness defect.

Narrow correction contract:
- in YoutubeDLCompat.executeWithQuiescence, classify the request before choosing the effective anonymous process identity;
- for processId == null and requestRequiresMutation(request) == true, use an identity in the existing mutation: namespace so its marker is selected by recoverRuntimeMutationNativeDebt;
- ordinary anonymous non-mutating requests remain consumer:;
- caller-supplied Download/Terminal/History process identities remain unchanged;
- reuse the existing YtdlpNativeProcessBarrier marker/token model;
- do not add a second durable store;
- do not broaden recovery to indiscriminately terminate every marker;
- preserve reader fencing, exact-generation recovery, runtime usability validation, mutation publication semantics, cancellation/revocation behavior, and custom updater non-reentrancy.

Required focused correction evidence:
- anonymous generic mutation-classified request gets mutation:-scoped effective native identity;
- ordinary anonymous request remains consumer:-scoped;
- caller-supplied processId remains unchanged;
- unresolved anonymous generic mutation generation is discoverable by the existing mutation recovery selector after process-local authority release/recreation;
- later reader/updater progress resumes after exact recovery;
- existing BUG-UPDATER-04 focused coverage remains preserved;
- historical focused failure evidence remains preserved and NOT_VERIFIED unless independently resolved.

No implementation push is authorized until the correction is implemented, reviewed, committed, exact-SHA gates pass, and the tested corrected SHA is the SHA pushed by normal fast-forward.

The separate debug-package applicationId isolation follow-up remains mandatory and separate from this same-root correction.

INDEPENDENT EXECUTION: NOT EXECUTED
