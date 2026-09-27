# BUG-UPDATER-02 clean-basis revalidation

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: 84d544acb008193f486f618ead9f1c1f30ab3dbb
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
live_completed_implementation_head: 31f55aca76efb1b956567fca5325b2575779a835
active_tooling_wave_inspected: NO

verdict: OPEN P2 / CONFIRMED
new_finding_ids: 0
count_change: 0
canonical_p2: 17
primary_lens: L3 Concurrency & authority DEEP
independent_execution: NOT EXECUTED

Source result: UpdateUtil.updateYoutubeDL still creates PROCESSING when updatingYTDL is true but does not return it, then sets updatingYTDL=true and never clears it. There is no process-wide ownership primitive, source generation, runtime provenance carrier, or stale-completion fence.

Settings persists ytdlp_source before starting its requested update; startup auto-update independently calls the same helper. Two valid requests can therefore mutate the same yt-dlp runtime concurrently, and an older completion can leave the installed runtime inconsistent with the newer persisted source.

UpdateUtil.kt has the same blob on CLEAN basis and current completed implementation 31f55aca. Active tools/remediation work was not inspected.