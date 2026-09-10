# Independent Track A checkpoint — canonical count reconciliation 3

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Implementation in-progress diff inspected: NO
- Review branch HEAD observed before this checkpoint: `36a721f9b22c8c4f5901dc22dffe18e352c8fa78`
- Plan: `fada33a7eed86b1fa2c07065af66f14bf4d24714`
- Governing checklist: `4e8f161dbe7a2dcb9915a223066bbf96d3aa0ee7`
- Ledger reference only: `899328bc91e4008e39a658387396a0106c8666ec`
- Authoritative ledger modified: NO
- Verdict: `NOT_CLEAN`
- Independent execution: NOT EXECUTED

## Purpose

Correct one historical bookkeeping error before any new blocker increment. This checkpoint changes blocker counting only; it does not introduce, close, or reclassify a semantic finding.

## Exact chronology of the duplicated subtraction

`BUG-HISTORY-UNDO-PLAYLIST-01` is an evidentiary alias/subcase of F17 `BUG-HISTORY-01`, not a second semantic root.

The exact review-branch chronology is:

1. `e2a4468cb872d220c9c6aea3bb6f4c89767c242a` (`2026-09-10T03:42:22Z`) created `history-undo-playlist-membership.md` and provisionally counted the alias as a new P2.
2. `1472a1b7fdbd1d6ab8bebc202263425381e7c486` (`2026-09-10T04:36:32Z`) created `false-positive-scope-audit.md`. That audit explicitly identified `BUG-HISTORY-UNDO-PLAYLIST-01` as duplicate F17 evidence and already removed one count, correcting its working recount from P2 27 to P2 26.
3. The commit history for `history-undo-playlist-membership.md` contains only the original `e2a4468...` commit. There is no later semantic re-addition of that alias after the false-positive audit.
4. `4aab558fcb69eff4e25dffcafcc69b734250483c` (`2026-09-10T06:07:55Z`) created `history-undo-count-reconciliation.md` and subtracted the same alias again, moving its stated count from P2 23 to P2 22.

The fourth step is therefore a duplicate subtraction. The finding disposition in that checkpoint is correct; only its numeric delta is wrong because the alias had already been removed by `false-positive-scope-audit.md`.

## Downstream count propagation

The later canonical chain inherited that one-low basis:

- `history-undo-count-reconciliation.md`: reported P2 22; corrected value is P2 23.
- `hardsub-generation-backup-revalidation.md`: adds distinct `BUG-HARDSUB-GENERATION-01`; reported 23, corrected 24.
- `group-relations-scheduler-consumer-generation.md`: adds distinct `BUG-GROUP-TXN-01`; reported 24, corrected 25.
- `canonical-count-reconciliation-2.md` correctly removes later duplicate increments, but it starts from the inherited one-low chain; reported 24, corrected 25.
- `observe-source-identity.md`: adds distinct `BUG-OBSERVE-SOURCE-IDENTITY-01`; reported 25, corrected 26.
- `observe-source-update-identity.md` is a same-root subcase and remains +0.

No semantic root is added by this reconciliation. No previously rejected false positive or duplicate alias is restored.

## Corrected canonical working count

Immediately before this checkpoint the handoff numeric field says:

- `P0 2`
- `P1 3`
- `P2 25`

Correcting the duplicated History Undo subtraction yields:

- `P0 2`
- `P1 3`
- `P2 26`

The current handoff's enumerated P2 open-root inventory contains 26 distinct retained roots, which is consistent with this corrected numeric count.

Overall verdict remains `NOT_CLEAN`.

Review Basis remains `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`.

INDEPENDENT EXECUTION: NOT EXECUTED
