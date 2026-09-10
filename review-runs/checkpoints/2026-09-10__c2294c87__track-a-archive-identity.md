# Independent Track A checkpoint — archive identity consumer

- Fixed Review Basis: `c2294c87781c8bfd5d3dbe0ac9ffce24daddba0d`
- Scope: existing P2-B global archive authority
- Classification: confirmed additional subcase `P2-B10`; no new finding count.
- Authoritative ledger not modified.

## Evidence

YTDLnisX manual duplicate detection reads the global download archive line-by-line, discards the first field, preserves only token 2, then tests `item.url.contains(id)`. A match marks the Download duplicate; `queueDownloads()` removes marked duplicate IDs from the set that will be queued.

ObserveSourceWorker performs the same projection to token 2 and raw-URL substring test, then records the item as a duplicate/queue skip.

Current yt-dlp source defines archive identity as `make_archive_id(extractor, video_id)`, returning `<lowercase extractor key> <video id>`, and checks membership using the full generated archive id. Therefore the app projection is strictly weaker than the producer's identity contract.

## Concrete impact

An archive entry for extractor E1/id X can suppress a distinct requested URL handled by E2 whenever its raw URL text contains X. Substring matches can also suppress a distinct media URL whose unrelated path/query text happens to contain a prior archive ID. The app can therefore silently classify/skip media that yt-dlp itself would not regard as the same archive identity.

## Invariant / acceptance

Global archive duplicate authority must preserve the producer's complete semantic archive key (including extractor identity) or use an equivalently strong canonical source identity. Never reduce archive authority to bare-ID substring membership.

P2-B closure should cover both:
1. B8 temporal authority: generation-private archive before app primary success, idempotent promotion afterward;
2. B10 identity authority: exact/equivalent full archive identity matching in manual and Observe consumers.

No additional P2 count: this shares P2-B's durable global archive authority and should be corrected in the same semantic boundary.

INDEPENDENT EXECUTION: NOT EXECUTED
