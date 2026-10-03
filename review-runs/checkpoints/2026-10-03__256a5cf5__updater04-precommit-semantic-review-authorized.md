# BUG-UPDATER-04 exact dirty-candidate semantic review authorized

review_parent_sha: 1fb7435525ecc6a3e8a8485a5c915a514c5a619e
implementation_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Pre-commit gates are complete on the current dirty candidate.

Before any commit, require one read-only exact-candidate semantic review of the entire dirty range against pinned base 256a5cf5.

Required review:
- enumerate every dirty production and test file;
- inspect shared runtime authority semantics end-to-end, not diff-only;
- prove ordinary consumers acquire before final native launch and retain authority through exact native quiescence/recovery;
- prove updater/custom self-update mutation authority covers the destructive interval and excludes new consumers;
- prove custom --update-to does not reenter ordinary consumer authority;
- inspect process-death/unresolved-native admission semantics and reuse of YtdlpNativeProcessBarrier;
- inspect failure/cancellation release and later-progress paths;
- enumerate production callers/dispatchers that can wait and assess main-thread blocking, lock order, starvation, and cancellation;
- verify no unrelated root/config/dependency/schema expansion;
- classify the test-only readiness and direct-role diagnostic edits without weakening regression semantics;
- reconcile the preserved historical direct-role false-return evidence with the later isolated PASS without declaring the old failure explained;
- recommend logical commit shape: one combined forward commit or production + test commit, maximum two;
- do not commit, push, publish, edit, or rerun tests.

If any production semantic conclusion is unsupported, mark NOT_VERIFIED and stop commit authorization.

BUG-UPDATER-04 remains OPEN P2.
