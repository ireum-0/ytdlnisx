# BUG-SCHEDULER-01 — ordinary dh2/JBR ACL diagnostic stop reconciled

date: 2026-10-08
checkpoint_kind: IMPLEMENTATION_DIAGNOSTIC_STOP_COMPLETION_REVIEW
checkpoint_status: FINAL
manual_review_run: NO
review_start_parent: 2b3491c929bc97bdca25f0493c0dd16a4cc5169c
implementation_pinned_sha: c34b8aa57e01803c9960e4ad873d1ed5b68e019c
applicable_protocol_blob: 0a32a5df9db4fe5a92ca7f921e1b939d1159eb79
private_handoff_parent: 0f3434f8ff5063f42954b4eea8ff13cfd6362dc7
existing_production_root: BUG-SCHEDULER-01 OPEN P2
new_canonical_root_ids: NONE
canonical_production_root_count_delta: 0
independent_host_execution: NOT_EXECUTED
production_source_changes: NONE_REPORTED
semantic_closure: NOT_VERIFIED

## Evidence received, provenance and limits

The user supplied the full summary of an out-of-band, user-directed, read-only Windows diagnostic (not the prior private V2 prompt-integrity/CI assignment). Its sealed report is reported at:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-windows-gradle-token-diagnostic-20261008-9TYRrR/WINDOWS_GRADLE_ACCESS_DENIED_DIAGNOSTIC_REPORT.md

This Windows-local file was not available for independent readback from GitHub. The observations below are implementation-agent report evidence, NOT independently reproduced runtime assertions:

- Original failure remained Java RandomAccessFile(lockFile, "rw") FileNotFoundException "Access denied" at C:/Users/dh2/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13-bin.zip.lck, before tryLock, wrapper startup or compilation. Historical native Win32 code and failed JVM tokens were not captured.
- Current controller and one benign java -version child: user dh2/SID ending 1001, medium integrity, non-elevated, zero restricting SIDs, matched identity; child exited 0; Android Studio JBR 21.0.8 and wrapper Gradle 8.13 retained.
- In-memory AccessCheck: 25/25 successful, all requested rights allowed for controller/Java child on the existing lock read/write and parent create/traverse access. These ACL results do not prove the absence of file sharing conflicts or security-filter denial.
- Earlier restricted observer's seven restricting SIDs and lack of matching DACL write rights remain a plausible but unproven mechanism for the historical Java failure. Do not retroactively attribute earlier JVM tokens.
- User reports local worktree HEAD c34b8aa57e01803c9960e4ad873d1ed5b68e019c, dirty tree df2d52c20c6928bf74b7d1c4f230483189b5aa5b, three unstaged files and empty index unchanged, five protected worktrees/evidence untouched, and 12 regression methods still unexecuted. These host states were not independently inspected from GitHub.
- No Gradle startup, build, app test, commit or push occurred in the received diagnostic.

## Independent disposition

The current unrestricted Java child is shown by reported AccessCheck to have the required ACL rights. The *historical* root cause remains NOT_VERIFIED. ETW StartTraceW error 5 belongs to the observer and is not proof of the historical Java lock-open cause. No ACL, cache, lock file, OS security or source mutation is justified by the evidence.

The out-of-band report must not be classified as execution of the persisted private prompt-integrity V2 assignment, which remained PERSISTED_NOT_STARTED in the prior handoff. Reconcile that stale routing explicitly; preserve the historical prompt unmodified.

## Governed next execution boundary

Reviewer may route a **new separate narrow diagnostic** (not an implicit continuation of private V2) only after persistence/readback and handoff reconciliation, with:
1. normal non-elevated dh2 execution context, actual chosen JBR 21.0.8, original wrapper Gradle 8.13, original cache and exact protected candidate proven;
2. no ETW/privilege escalation/ACL mutation, no lock deletion/cache relocation/cleanup, no source mutation, commit, push, or unrelated work;
3. preflight original cached distro exists and would not trigger network download; bounded exactly one `gradlew.bat --offline --no-daemon --version` startup under that actual context, with finite timeout and evidence captured prior to cleanup;
4. capture launch identity (controller and child where permitted), stdout/stderr/exit, exact lock error if failed and evidence at the first failure;
5. if succeeds, stop and report WRAPPER_STARTUP_PASS only (no source correctness/test pass); if fails, stop and report without blindly retrying;
6. preserve candidate/index and all other protected worktrees; later focused scheduler regression requires a separately governed implementation verification decision.

Independent production closure remains OPEN; repository-wide CLEAN must not be claimed.
