# Authoritative Correctness Remediation Master Plan Manifest

This directory contains the canonical versioned Master Plan for `ireum-0/ytdlnisx`.

## Authority and pinning

- Branch: `plan/remediation`
- Canonical text: lexical-order concatenation of every file under `remediation/master-plan/parts/*.part`
- All parts MUST be read from the same exact pinned Plan SHA.
- Pin the remote `plan/remediation` SHA at run bootstrap and do not silently switch Plan SHA mid-run.
- `checkpoint/pre-baseline-review`, `review/remediation`, and `ledger/remediation` remain live/fresh inputs during the run.
- Fresh current production source is authoritative for source-state facts. The pinned Master Plan is authoritative for workflow, gates, invariants, review discipline, branch authority, and execution policy.
- Part boundaries are transport/versioning boundaries only; they have no semantic meaning.

## Canonical read and verification

```bash
git fetch origin
PLAN_SHA=$(git rev-parse origin/plan/remediation)
tmp=$(mktemp)
: > "$tmp"
for p in $(git ls-tree -r --name-only "$PLAN_SHA" remediation/master-plan/parts | sort); do
  git show "$PLAN_SHA:$p" >> "$tmp"
done
sha256sum "$tmp"
cat "$tmp"
```

Expected whole-plan properties for this plan publication:
- SHA-256: `4f00525a2c3cd94ec81e7d32e3de5a50229a64f8b90be4ca1ec0413539a2e49e`
- Bytes: `136910`
- Lines: `3139`
- Parts: `13`

## Part inventory

| Part | Bytes | Git blob SHA | Part SHA-256 |
|---|---:|---|---|
| `0001.part` | 10966 | `14b213dcea6e20deb8ae97cc819c35c7b7cdbadb` | `cd5be35327855ae5c339184c738a0513fe77f3be6577aba65945918d0dced9ab` |
| `0002.part` | 10927 | `23e6bb98e20c1b29b4012c1394268a5ba9e49b1a` | `766e41a497cc60854cd198736c99540ab18abaaa4cda4d706246f1aa0274d0c8` |
| `0003.part` | 10967 | `77dae7dc12c9fa734f6092d126a6fb23d8d862a3` | `5e51df8f637c27cc842f066742da416aa9489211f62aeeba3988041efd22181e` |
| `0004.part` | 10977 | `4d2958d03223fbf73e066eb52fdb696a16bb97cd` | `4c0f807dfd0ec76995f3ba0769970629bcfaa55704ccb8c6d592e3bfc6db7ae5` |
| `0005.part` | 10870 | `ce0144325ad210d20fb18c7129699b888ed6986a` | `da2789d4b043fb8e1c319d517f21662ca467e8e0d43dcc518e3222653c684e90` |
| `0006.part` | 10929 | `a101865572dd441f217fc5f3a8124905c106fec6` | `19004b7c86aede3750598e78a6079a27c566d0671e182468c4ff3c93f01c3bcd` |
| `0007.part` | 10999 | `0183b1d932062738187f19ce1ee4f6dfc577238e` | `d8b314b81cf9ff7a6384d7a484b2bff1477978556bf1e106954f79c89cdf1c06` |
| `0008.part` | 10971 | `585347d7805195d3d3aa35b772d5034f55ce164c` | `2d40b187225c86e9eaa5c42ca4e3491cacc5d6f291b2b3329254d3a2ae982a76` |
| `0009.part` | 10960 | `cf605dc49dddd1d6478c486d25d47bbedf4c33e0` | `e6bf237c31478834695479dca9ce30cffe7e89c9f85e26e1772d70ae9e782bcc` |
| `0010.part` | 10974 | `5236aed8415d19854b8379fcd9fe6f78f894b987` | `e2c55cd9ac956dc8af6b90168edc4d4297d4327770c0a74eb8f1643bb94e31ad` |
| `0011.part` | 10929 | `7dfeb842ad2732d2cfc91ac9ca0cdf537328ab8b` | `bed07a6e75f5c7275376a8dab3dc564f015f4f0a25ff295fa00f048fea336b92` |
| `0012.part` | 10982 | `3c5dbb9ae732d2e906f7e0485349a8058a7e8f07` | `f3e9442a78cc4ec79e9e729c2e2615036d55e07264fb834095fbc5a5e6e37546` |
| `0013.part` | 5459 | `202a15f58c38665a72a2c4efb1b15eb7f91ab3d9` | `13c04bb50e1e624ac9fc36211adea5e184706b4d31a74acafc0b3a3b2d37211f` |

## Historical transport note

An earlier intermediate commit attempted to transport the plan as one gzip blob. Verification showed that transport was incomplete, so that artifact is non-authoritative and is removed in the verified multipart publication. Do not use the old `.md.gz` object even when inspecting historical plan commits.

## Branch roles

- `plan/remediation`: versioned planning/governance only
- `checkpoint/pre-baseline-review`: semantic implementation and tests
- `review/remediation`: independent exact-SHA review records
- `ledger/remediation`: independently established closure records
