# Build environment stabilization — local.properties dependency removal authorized

review_parent_sha: e1e6c32538fe0dc25ff295884dad09565fbfa22c
implementation_sha: e97e5b975e2bde7b7de3d6071799f8c4b8216f41
canonical_count_change: 0

status: BUILD_ENVIRONMENT_MAINTENANCE_AUTHORIZED

Observed blocker:
A fresh isolated BUG-UPDATER-02 worktree stopped before compilation because local.properties was absent. The updater candidate and four regression additions remain preserved and uncommitted; git diff --check passed; no tests, staging, commit, push, or device action occurred. A bulk preference-clear wakeup concern remains pending independent source review for BUG-UPDATER-02.

Root cause class:
local.properties is ignored and therefore absent in new worktrees. Current repository CI explicitly creates an empty local.properties before Gradle runs, showing that build configuration currently depends on the file's presence even when CI does not place SDK location there.

Authorized infrastructure correction:
- keep the preserved BUG-UPDATER-02 dirty worktree untouched;
- perform this maintenance in a separate clean isolated worktree based on e97e5b9;
- separate Secrets Gradle Plugin input from local.properties using a dedicated ignored secrets.properties plus a checked-in non-secret defaults properties file;
- remove CI dependence on creating local.properties when the resulting clean checkout build proves it unnecessary;
- add secrets.properties to gitignore;
- do not read/copy/print any existing local.properties or other secret-bearing file;
- establish ANDROID_HOME from the already-installed Android SDK without using local.properties, only if the user-level variable is absent or incorrect;
- do not set deprecated duplicate SDK variables unless required by current official tooling;
- preserve all unrelated source behavior.

Verification:
- clean isolated worktree with no local.properties present;
- verify Gradle configuration and :app:compileDebugKotlin -x lint succeed using the machine SDK environment;
- verify CI workflow no longer needs touch local.properties;
- git diff --check;
- no device tests required.

Publication:
If the maintenance diff is minimal and the no-local.properties compile gate passes, one logical forward maintenance checkpoint may be committed and normal-fast-forward pushed. Publication is not a correctness-root closure and does not change canonical P0/P1/P2 counts.

After publication, the preserved BUG-UPDATER-02 candidate must be reconciled forward onto the new implementation HEAD without rebase/amend/history rewrite, then independently reviewed for the recorded bulk preference-clear wakeup concern before that root is published.

INDEPENDENT EXECUTION: NOT EXECUTED
