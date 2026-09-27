# BUG-ABI-01 clean-basis revalidation

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: f43405f9c74a6419d44e3218ac865bf510d1cb4a
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
live_completed_implementation_head: 31f55aca76efb1b956567fca5325b2575779a835
active_tooling_wave_inspected: NO

verdict: OPEN P2 / CONFIRMED
new_finding_ids: 0
count_change: 0
canonical_p2: 17
primary_lens: L5 Platform contract closure DEEP
independent_execution: NOT EXECUTED

Source result: release ABI splits still publish x86, x86_64, armeabi-v7a, arm64-v8a, plus universal output, while checked-in FFmpeg/ffprobe native binaries and ffmpeg_payload.zip exist only for arm64-v8a.

Bundled FFmpeg payload installation derives assets/bin/<primary ABI>/ffmpeg_payload.zip and logs installation failure without making startup fail. YoutubeDLCompat only supplies --ffmpeg-location when native libffmpeg.so + libffprobe.so and extracted payload libs are all present.

DownloadWorker hard-sub runtime resolution still falls back to nativeLibraryDir/libffmpeg.so even when no usable candidate exists. On non-arm64 published outputs this is not a proven executable runtime.

The relevant build/runtime blobs are identical on CLEAN basis and current completed implementation 31f55aca. Active tools/remediation work was not inspected.