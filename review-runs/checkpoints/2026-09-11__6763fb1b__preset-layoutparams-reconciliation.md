# Independent exploratory review checkpoint — Preset/LayoutParams crash reconciliation

Date: 2026-09-11
Review mode: independent exploratory review while the implementation agent is WORKING_FROZEN
Reviewed implementation basis: `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`
Governing Master Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`, §9.10 Preset/LayoutParams crash
Governing checklist: `REVIEW_CHECKLIST_V6_OPERATIONAL.md@4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
Disposition: **REJECTED AS A CURRENT-BASIS BLOCKER / NO CURRENT DEFECT CONFIRMED AT THE FIXED CLEAN BASIS**

## Freeze discipline

The active Luna review-fix branch state was intentionally not inspected or used as evidence. This review uses only the independently CLEAN contiguous basis `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`. Concurrent review records that mention an unreported in-progress implementation SHA are not adopted here as implementation-completion or CLEAN-basis evidence.

## Governing hypothesis

Master Plan §9.10 records a prior-review crash class involving an assumed concrete Android `ViewGroup.LayoutParams` subtype during preset application/reconfiguration and explicitly marks it `REQUIRES FRESH SOURCE CONFIRMATION`. The required invariant is that layout mutations must match the actual current parent/layout contract across supported resource variants, restored fragments, preset switching, and quick-download use.

## Exact-source review

At the reviewed basis:

1. `DownloadBottomSheetDialog.applyPreset()` does not read, cast, or mutate an existing fragment view's `layoutParams`. It derives an applied `DownloadItem` through `DownloadViewModel.applyDownloadPreset()`, replaces `currentDownloadItem`, creates a new `DownloadFragmentAdapter`, assigns it to the `ViewPager2`, and selects the audio/video page for the applied type.
2. `DownloadViewModel.applyDownloadPreset()` is a data-mapping boundary: it chooses/copies the target `DownloadType`, resolves format, and delegates to `DownloadPresetMapper.applyTo()`. It contains no view or layout mutation.
3. `DownloadAudioFragment` and `DownloadVideoFragment` each contain one relevant concrete `LinearLayout.LayoutParams` construction for `downloadContainer`, gated by `nonSpecific`. In both cases the parameter is assigned directly to `downloadContainer.layoutParams`; there is no cast from an unknown current subtype.
4. In `fragment_download_audio.xml` and `fragment_download_video.xml`, `@id/downloadContainer` is a direct child of a `LinearLayout`. The concrete parameter class written by the fragment therefore matches the actual parent contract.
5. The relevant audio/video fragment source contains no `removeView`/`addView` reparenting path that could invalidate that parent relationship before the assignment.
6. The repository's alternative layout resource directories at this basis (`layout-land`, `layout-sw600dp-land`, `layout-sw950dp-land`, `layout-sw1240dp-land`) do not contain alternate `fragment_download_audio.xml` or `fragment_download_video.xml` resources. There is also no `layout-sw600dp` or `layout-sw600dp-port` directory. The reviewed base audio/video parent contract therefore remains the selected contract across those supported configuration variants.
7. Quick-download preset selection feeds `initialPreset` into the same fragment-adapter creation path. Saved-instance restoration rebuilds the dialog/adapter from the stored `DownloadItem` and type rather than reparenting an existing `downloadContainer` into a different parent class.

## Disposition

The fresh-source requirements in §9.10 do not establish a current crash path at the fixed CLEAN basis. The only concrete subtype write located in the relevant production fragments is compatible with the actual direct parent, and preset application reconstructs the adapter/fragments rather than blindly casting or mutating an unknown prior `LayoutParams` subtype.

This rejects §9.10 as a current-basis blocker. It does **not** claim the historical crash class never existed, and it does not rewrite the Master Plan. Fresh future code or resource variants may retrigger the hypothesis and require a new review.

Canonical blocker-count delta: **0**.
Canonical blocker count remains **P0 3 / P1 3 / P2 26**.
Contiguous independently CLEAN Review Basis remains `6763fb1be188fb000b9e9a665c7b3fe349fd40ca`; this exploratory rejection does not advance it.

The active `BUG-HISTORY-DUPLICATE-IDENTITY-01` Luna review-fix remains frozen from inspection until explicit completion reporting and is unaffected by this checkpoint.

INDEPENDENT EXECUTION: NOT EXECUTED
