# BUG-RESUME-01 clean-basis remediation-ready revalidation

checkpoint_kind: EXPLORATORY_CURRENT_BASIS_REVALIDATION
review_parent_sha: 588ba6e9a7074597636d46ce35cccac4635b12be
clean_review_basis: 74f57e695db30b701ad429af311c39a763bfe086
implementation_beyond_clean_basis_inspected: NO

verdict: OPEN P2 / CONFIRMED / REMEDIATION-READY
new_finding_ids: 0
count_change: 0
canonical_p2: 18
primary_lens: L5 Platform contract closure DEEP
supporting_lenses:
- L2 Identity & provenance
- L6 Cross-feature semantic propagation
independent_execution: NOT EXECUTED

## Existing hardening to preserve

The notification capability transport is already meaningfully narrowed.

Paused-download Resume notifications carry the Download ID plus expected execution identity. Retryable Error notifications carry the Download ID plus retry operation/attempt capability. `ResumeActivity.handleIntents()` passes those exact values into `resumePausedDownloadAndWait()` or `retryFailedDownload()`, and only cancels the notification after the state-changing decision is not blocked.

`ResumeActivity` is also non-exported in the manifest.

Those identity checks are not the defect and must remain intact.

## Exact defect

Before `handleIntents()` consumes either capability, `ResumeActivity.onCreate()` changes its Activity window type:

- API 26+: `TYPE_APPLICATION_OVERLAY`
- API 24-25: `TYPE_SYSTEM_ALERT`

The manifest does not declare `SYSTEM_ALERT_WINDOW`, and this workflow does not request or prove special overlay authority.

The PendingIntents themselves are ordinary app-owned `PendingIntent.getActivity()` launches. The retry/resume operation therefore introduces a platform capability requirement that is unrelated to the semantic operation and can prevent the exact recovery capability from ever reaching its state transition.

No Download race is needed. A fully valid, current resume/retry capability can fail at Activity/window setup before Download authority is consumed.

## Exact invariant

A valid app-owned notification Retry/Resume capability must be consumable through an ordinary supported application Activity/window path.

Window presentation is not semantic authority. It must neither:
- require unrelated special-access permission; nor
- weaken the exact Download execution/retry capability checks.

The operation succeeds only when the exact capability is accepted by the Download state transition. A window/setup failure must leave the notification/recovery affordance unconsumed.

## Narrow implementation boundary

Remove the privileged `window.setType(TYPE_APPLICATION_OVERLAY / TYPE_SYSTEM_ALERT)` branch from `ResumeActivity`.

Keep:
- the Activity non-exported;
- ordinary theme/background/layout behavior needed for the bottom-sheet UI;
- `PendingIntent.getActivity()` notification wiring;
- exact `executionId` validation for Resume;
- exact retry operation/attempt validation for Retry;
- notification cancellation only after a non-blocked semantic result.

Do not add `SYSTEM_ALERT_WINDOW` to the manifest. There is no demonstrated overlay use case in this flow.

If full-screen transparent presentation needs adjustment after removing the privileged type, use ordinary application-window attributes/theme/layout flags only. Presentation must remain downstream of normal Activity authorization, not create a new special-access gate.

## Forbidden shortcuts

- declaring `SYSTEM_ALERT_WINDOW` just to preserve the current window type
- requesting overlay permission when the user taps Retry/Resume
- moving the semantic mutation into a BroadcastReceiver solely to avoid fixing the Activity window
- dropping expected execution/retry capability checks because the Activity is non-exported
- cancelling the notification before the exact Download transition succeeds
- treating repeated tap/manual in-app recovery as proof that the notification path is acceptable
- API-specific privileged fallbacks on 24-25 or 26+

## Acceptance matrix

- API 24/25, no overlay permission: valid paused Resume PendingIntent reaches exact `resumePausedDownloadAndWait()`
- API 26+, no overlay permission: same
- API 24/25, retryable Error PendingIntent: exact retry capability reaches `retryFailedDownload()`
- API 26+, same
- stale/mismatched executionId remains blocked after window correction
- stale/mismatched retry operation/attempt remains blocked after window correction
- missing executionId on Resume remains blocked/no-op as today
- blocked semantic result leaves the notification available rather than consuming it
- successful semantic result cancels only the corresponding notification
- Activity remains non-exported
- manifest contains no `SYSTEM_ALERT_WINDOW` requirement for this workflow
- repeated tap after a blocked result remains safe/idempotent
- production-path test uses actual `NotificationUtil -> PendingIntent -> ResumeActivity -> DownloadViewModel` wiring on both supported API bands; intent-construction-only tests are insufficient

## Test gap

The bounded CLEAN-basis search found no focused production test that launches the real Resume/Retry notification PendingIntent on API 24/25 and API 26+ without overlay authority while also asserting the exact Download capability checks.

The source at `74f57e69` still sets the privileged window type before state transition, so the existing root remains open.
