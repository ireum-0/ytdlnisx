# Debug/test package isolation — device baseline policy revision

review_parent_sha: 9dfd9fa68fdeb496a9006e5278d2a6ccaf851394
implementation_sha: e97e5b975e2bde7b7de3d6071799f8c4b8216f41
canonical_count_change: 0

status: DEVICE_VALIDATION_READY_WITH_CLEAN_RELEASE_BASELINE

User decision:
The previous requirement for a pre-existing com.ireum.ytdl installation is superseded. When the target device has no release package, the validation may first establish a clean trusted com.ireum.ytdl baseline and then perform the accepted e97e5b9 debug/test isolation check.

The closure claim is package/install/instrumentation isolation, not preservation of historical personal app data that was absent at validation start.

Source/artifact acceptance at e97e5b9 remains unchanged. No source/config change, commit, or push is required.

INDEPENDENT EXECUTION: NOT EXECUTED
