# BUG-UPDATER-04 pre-commit independent review — same-root durable mutation-debt residual

review_parent_sha: d75385877d238b0d8dd29b3b70630c2eddd1b3af
implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Independent reviewer decision:
DO_NOT_COMMIT

Evidence accepted from the protected dirty-candidate report:
- ordinary runtime reader acquisition has no durable mutation-debt check;
- mutation writers do check native namespace quiescence before mutation;
- generic mutation-owned/native debt is not shown to have a production recovery/admission dispatch after process-local writer release or restart;
- failure/cancellation coverage proves release/progress only when the updater stub fails before changing the runtime;
- failed publication/post-validation and unresolved mutation states remain unproved;
- the original direct-role recoverDownloadExecution false return remains unexplained and is not used as the semantic root.

Governing contract requires:
- newly admitted ordinary consumers remain excluded until promoted runtime is verified usable or mutation failed safely;
- unresolved durable native generations are not treated as absent because process-local ownership disappeared;
- failure/cancellation leaves a usable prior runtime or an exact recoverable state before future progress.

Independent semantic root:
The current candidate ties ordinary-consumer exclusion primarily to process-local reader/writer ownership. If a mutation-owned native generation or publication remains unresolved after the writer's physical ownership is released, or after process restart removes process-local authority, ordinary reader admission is not proven to fail closed on that durable mutation debt before native launch.

This is a same-root BUG-UPDATER-04 residual, not a new finding.

Required correction contract:
1. Reuse YtdlpNativeProcessBarrier durable generation/marker semantics. Do not add a competing durable store/model.
2. Mutation-owned native generations must remain durably discoverable after process-local authority loss/restart.
3. Ordinary reader admission must fail closed while incompatible unresolved mutation-owned native debt exists.
4. Before a blocked reader may cross final validation/native launch, existing barrier recovery/quiescence must prove the incompatible mutation generation gone, or an already-established exact durable proof must exist.
5. Mutation failure/cancellation/post-validation failure must not release the system into a state where readers can observe or launch against a missing/partial/unverified runtime.
6. Later reader and later updater progress must resume after exact recovery/convergence.
7. Preserve custom --update-to non-reentrancy and all existing Download/native ownership semantics.
8. Do not implement BUG-UPDATER-02, BUG-UPDATER-03, or BUG-HISTORY-05.
9. If cancellation during synchronous pre-registration authority wait can still cross launch after caller cancellation, correct it within this same authority contract; otherwise provide exact proof that it cannot.
10. Preserve historical direct-role failure evidence; do not rerun it merely to seek green.

Required focused coverage before commit:
- unresolved mutation-owned native generation blocks a later ordinary consumer after writer release;
- equivalent durable debt still blocks/reconciles after process-local authority recreation/restart;
- failed publication/post-validation cannot admit an ordinary consumer until runtime usability/recovery is proven;
- later consumer and updater make progress after exact recovery;
- cancellation/revocation during pre-registration wait cannot produce an unauthorized late launch, if that path is source-reachable.

No commit, push, publication, count change, or closure is authorized.

BUG-UPDATER-04 remains OPEN P2.
