# BUG-UPDATER-04 commit-composition hard stop — exact manifest evidence required

review_parent_sha: b6534279588fe6a5307b8e48d9063ce9a299c14b
implementation_remote_sha: 256a5cf507b54adcca0342b82ddaf6e2d75a684e
active_root: BUG-UPDATER-04
canonical_counts: P0=0 P1=0 P2=4

Independent reviewer disposition:
COMMIT_COMPOSITION_NOT_AUTHORIZED
PRECOMMIT_VERIFICATION_REMAINS_GREEN
NOT_FIXED
NOT_CLOSED
NOT_CLEAN

Accepted stop-report facts:
- remote/local HEAD remains 256a5cf507b54adcca0342b82ddaf6e2d75a684e;
- all eleven recorded dirty-file hashes still match the previously verified candidate state;
- dirty inventory remains 9 modified + 2 untracked;
- focused verification remains 5/5 PASS;
- full YtdlpRuntimeAuthorityProductionWiringTest remains 12/12 PASS;
- no staging, edit, commit, new test, push, or publication occurred in the stopped commit attempt;
- implementation agent stopped because the prescribed local reports did not unambiguously identify which inherited dirty hunks belong to the authorized BUG-UPDATER-04 commit and which must remain protected/excluded.

Implementation-agent report location:
C:/Users/dh2/AppData/Local/Temp/ytdlnisx-updater04-commit-composition-stop-752943b51f14493295998c7010ef4261/REPORT.md

Reviewer conclusion:
- The prior logical-commit authorization was conditional on exact candidate composition.
- That condition is not presently proven.
- Do not infer commit membership from filenames, expected center-of-gravity files, test success, or semantic plausibility.
- Do not stage whole files when they may contain inherited unrelated hunks.
- A reviewer-owned explicit commit path/hunk manifest and excluded protected-state inventory are required before commit creation can resume.

Required next evidence:
For all 9 modified + 2 untracked paths, obtain non-mutating raw composition evidence sufficient to distinguish:
1. BUG-UPDATER-04 authorized hunks/content introduced by the current durable-mutation-debt continuation;
2. inherited/protected unrelated hunks/content that must remain excluded;
3. files that are entirely authorized;
4. files that are entirely protected/excluded;
5. any mixed file requiring exact hunk-level staging.

The evidence collector must not decide authorization. It may label provenance hypotheses only when backed by exact diff/report evidence; final inclusion/exclusion remains reviewer-owned.

No staging, index mutation, source/test edit, commit, push, stash operation, reset, clean, or history rewrite is authorized.

INDEPENDENT EXECUTION: NOT EXECUTED
