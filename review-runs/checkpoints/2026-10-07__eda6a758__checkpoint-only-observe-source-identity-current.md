# Checkpoint-only production reconciliation — Observe source identity

checkpoint_kind: CHECKPOINT_ONLY_PRODUCTION_FINDING_RECONCILIATION
checkpoint_status: FINAL
review_parent_sha: 56d51e6a6040629ccc22ba325e1927fa2d17eeb7
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

## BUG-OBSERVE-SOURCE-IDENTITY-01 — VERIFIED_OPEN P2

This is a distinct historical checkpoint-only production root.

Current exact source still proves at least the USER-source ownership half:

- ObserveSourcesItem is @Entity(tableName="sources") with only auto-generated numeric id as primary key;
- no persisted canonical semantic-source key or unique source-identity index is declared;
- ObserveSourcesRepository.insert()/insertAndSchedule() perform
  checkIfExistsWithSameURL(item.url) followed by a separate insert;
- ObserveSourcesDao.checkIfExistsWithSameURL() is exact raw USER url equality;
- execution elsewhere canonicalizes supported playlist/source URLs, so raw persistence identity is weaker
  than execution semantic identity;
- two concurrent raw-identical creates can both pass the pre-check before either insert commits;
- canonical-equivalent raw forms can also become different source rows;
- each inserted numeric id owns an independent recurring OBSERVE<id> execution namespace.

Therefore one semantic USER Observe source can still fork into multiple durable recurring owners.

The historical root also included non-atomic Download duplicate publication under enabled duplicate policy.
Current source now contains a stronger final insertNewWithDuplicateAdmission() path, so this checkpoint
does not claim that historical subcase remains unchanged. The root remains OPEN independently because
source-row semantic uniqueness is still missing.

Disposition:
BUG-OBSERVE-SOURCE-IDENTITY-01 = VERIFIED_OPEN P2.

Keep distinct from:
- BUG-OBSERVE-HANDOFF-01: current-generation/revocation authority for one existing source row;
- BUG-OBSERVE-02: configuration edit versus runtime-field ownership;
- BUG-OBSERVE-03: current valid generation retaining a WorkManager carrier.

Current correction boundary:
1. define and persist canonical USER Observe source identity;
2. enforce one-winner semantic source admission atomically at the DB mutation boundary;
3. cover both create and edit collision;
4. retain numeric id only as row identity, not semantic uniqueness authority;
5. preserve intentionally distinct managed automatic-keyword source semantics;
6. test concurrent identical create, canonical-equivalent create, and edit-to-existing-source collision.

Project inventory effect:
- add one distinct checkpoint-only production root;
- current state: OPEN P2;
- download canonical inventory unchanged.

No production source, implementation prompt, active implementation scope, Master Plan or ledger changed.
