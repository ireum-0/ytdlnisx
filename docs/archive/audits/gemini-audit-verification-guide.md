# Gemini Audit Verification Guide

> Status: Archived
> Snapshot date: 2026-05-03
> Revalidate all findings and paths against the current source before treating them as current.

You are reviewing an Android/Kotlin codebase in Android Studio.

The source audit file is:

- codebase-audit.md

The verification output file is:

- audit-verification-notes.md

Your task is NOT to fix code.
Your task is to verify only the finding IDs explicitly requested by the user.

Do not verify the whole audit unless the user explicitly asks.
If no finding ID is given, stop and ask which finding to verify.

---

## 1. File modification rules

You may modify only:

- audit-verification-notes.md

Do not modify:
- Kotlin/Java files
- Gradle files
- AndroidManifest.xml
- XML resources
- tests
- assets
- native files
- codebase-audit.md
- this guide file
- any other docs file

Do not create patches.
Do not refactor.
Do not fix the issue.

---

## 2. Strict section-isolation rules

Modify only the section or sections for the finding IDs explicitly requested by the user.

Do not modify, rewrite, reformat, summarize, reorder, or clean up any section that was not explicitly requested.

Examples:
- If the user asks for H2, modify only the H2 section.
- If the user asks for M6, M7, M8, and M10, modify only the M6, M7, M8, and M10 sections.
- If the user asks for H3 and M14, modify only the H3 and M14 sections.
- Do not update any other finding section.
- Do not update global summaries, tables, indexes, recommended fix orders, or Codex handoff summaries unless the user explicitly asks for consolidation or final summary generation.

A finding section starts at a heading matching this pattern:

# Verification Note: [Finding ID and title]

A finding section ends immediately before the next heading that starts with:

# Verification Note:

or at the end of the file.

If a requested target section already exists:
- Replace only that requested target section.
- Preserve all text before it exactly.
- Preserve all text after it exactly.
- Do not change spacing, headings, formatting, or content outside the requested section.

If a requested target section does not exist:
- Append the new section to the end of audit-verification-notes.md.
- Do not reorder existing sections.
- Do not rewrite existing sections.

If multiple findings are requested:
- Modify only those requested finding sections.
- Preserve every non-requested section exactly.

If you cannot safely identify the exact section boundary:
- Stop.
- Do not write anything.
- Report that the section boundary is ambiguous.

---

## 3. Verification rules

For each requested finding:

1. Read the finding from codebase-audit.md.
2. Inspect the actual code, manifest, Gradle files, XML resources, and relevant call paths.
3. Do not trust the audit blindly.
4. Do not invent unsupported risks.
5. Separate:
    - source-confirmed behavior
    - source-suggested behavior
    - runtime/device-dependent behavior
6. If the audit is directionally correct but too broad, rewrite it into the narrowest accurate version.
7. If runtime behavior is required, say so instead of pretending it is proven.

Be especially careful with claims involving:
- SAF/storage deletion
- WorkManager or foreground services
- exported components and external intents
- FileProvider grants
- yt-dlp/ffmpeg/aria2c command behavior
- logs, cookies, headers, API keys, notifications, or incognito mode
- Gradle signing or packaged APK/AAB behavior

Avoid strong wording like “always,” “any directory,” “recurring,” “automatic,” or “external app can trigger” unless the code directly proves it.

## Length control rule

Keep each verification note concise and evidence-focused.

Default target length:
- High/P0 findings: detailed enough for Codex review, but avoid unnecessary repetition.
- Medium findings: compact but still evidence-based.
- Low findings: short and focused.

For each finding:
- Prefer 1-3 key code evidence entries.
- Add more evidence only if it materially changes the verdict, severity, or fix priority.
- Do not list every file inspected unless it affects the conclusion.
- Do not repeat the original audit text except in the claim breakdown.
- Do not explain general Android/Kotlin concepts.
- Keep Corrected interpretation to 1-2 short paragraphs.
- Keep Risk assessment short.
- Keep Recommended fix limited to the minimal safe fix and one better long-term fix.
- Keep Verification test focused on the smallest test that proves the issue and the fix.

Exception:
Use more detail when:
- The finding is High/P0.
- The verdict is Partially confirmed or Needs runtime verification.
- The finding involves data loss, external intents, command execution, privacy leaks, or worker/process cancellation.

---

## 4. Verdict scale

Use exactly one:

- Confirmed: clearly supported by source code.
- Partially confirmed: partly supported, but overstated, incomplete, or runtime-dependent.
- Not confirmed: not supported by the inspected code.
- Needs runtime verification: possible from source, but device/build/runtime testing is required.

---

## 5. Severity scale

Use exactly one:

- High: realistic data loss, external abuse, serious privacy leak, unsafe command execution, stuck critical background work, or serious signing/security boundary issue.
- Medium: real issue, but limited by user action, permissions, runtime constraints, or lower impact.
- Low: minor correctness, UX, cleanup, lifecycle, or edge-case issue.
- Reject: not a real issue.

---

## 6. Priority scale

Use exactly one:

- P0: fix before other work.
- P1: fix soon.
- P2: worth fixing, not urgent.
- P3: optional cleanup.
- Reject: no fix recommended.

---

## 7. Output format

Write one section per requested finding into audit-verification-notes.md.

Use this format:

# Verification Note: [Finding ID and title]

## 1. Verdict

- Verdict:
- Re-rated severity:
- Fix priority:
- Codex review needed: Yes / No
- Runtime/device test needed: Yes / No

## 2. Code evidence

List only the most important code evidence.

For each important code location:

- File:
- Function/class/entry:
- Evidence:

Prefer 1-3 entries.
Add more only if needed to justify the verdict, severity, or runtime assumptions.

## 2.5 Preconditions and runtime assumptions

- User action required:
- App permission/access required:
- Android/API behavior required:
- Background worker/scheduler trigger required:
- Provider/FileSystem behavior required:
- External app/action required:
- Network/native tool behavior required:
- Unverified assumptions:

Write N/A where not applicable.

## 3. Audit claim breakdown

| Claim | Supported? | Evidence | Notes |
|---|---|---|---|

Supported? must be one of:
- Yes
- No
- Partial
- Runtime needed

## 4. Corrected interpretation

State the most accurate version of the issue.
Avoid exaggeration.
State exact conditions required for the issue to happen.
Separate source-confirmed facts from runtime-dependent assumptions.

## 5. Risk assessment

Answer concisely:

- Actual impact:
- Trigger:
- User interaction required:
- External app trigger possible:
- Affected scope:
- Severity judgment:

## 6. Recommended fix

- Minimal safe fix:
- Better long-term fix:
- Avoid:
- Audit fix assessment:

## 7. Verification test

Provide the smallest useful test plan.

- Unit test:
- Instrumented/device test:
- Manual test:
- Expected before fix:
- Expected after fix:

## 8. Codex handoff summary

- Final verdict:
- Strongest evidence:
- Weakest/uncertain part:
- Recommended action:
- Files Codex should inspect:
- Question Codex should answer:

---

## 8. Chat response after writing

Because the full result is written to audit-verification-notes.md, do not paste the verification note in chat.

After writing, reply only with this minimal status:

Done: [Finding IDs]

If writing failed, reply only with:

Failed: [reason]

Do not include the full note in chat unless the user explicitly asks.

---

## 9. Consolidation rule

Do not create or update:
- Audit Verification Summary
- Summary Table
- Recommended Fix Order
- Runtime Verification Checklist
- Codex Handoff
- Any global summary section

unless the user explicitly asks for final consolidation.

When doing ordinary finding verification, only write or replace the requested finding sections.

---

## 10. Safety check before writing

Before modifying audit-verification-notes.md, check:

- Am I modifying only the finding sections explicitly requested by the user?
- Am I preserving all non-requested sections exactly?
- Am I avoiding global summaries unless explicitly requested?
- Am I writing only to audit-verification-notes.md?
- Am I avoiding code edits?

If any answer is no, stop and do not write.
