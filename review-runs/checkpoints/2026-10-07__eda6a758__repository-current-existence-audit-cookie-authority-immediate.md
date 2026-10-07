# Repository current-existence audit — Cookie authority immediate dispositions

checkpoint_kind: REPOSITORY_FINDING_CURRENT_EXISTENCE_AUDIT
checkpoint_status: FINAL
review_parent_sha: 4a10ab8b49a8359aba4c55b07190d703fc55b82d
current_implementation_sha: eda6a7589af3a19a97eb38e869b47dabaf74388b

canonical_scope_change: NONE
canonical_download_counts: P0=0,P1=0,P2=8

Already-audited Cookie root not recounted here:
- BUG-COOKIE-03 — Batch A, VERIFIED_CLOSED.

## BUG-COOKIE-01 — VERIFIED_CLOSED

Historical root:
Room cookie mutation could commit while an older cache/cookies.txt credential projection remained usable,
allowing yt-dlp to send credentials the user had deleted or disabled.

Current exact source closes that stale-authority path:
- delete, deleteAll, enable/disable, and update enter CookieProjectionCoordinator.mutateAndProject();
- the process-wide projection mutex retires the existing runtime cookie file before the Room mutation;
- after mutation, the same serialized operation rereads enabled rows and atomically writes/verifies the
  replacement projection;
- if mutation/read/write fails, the old runtime credential file is not restored;
- reviewed yt-dlp authentication consumers require CookieProjectionCoordinator.requireUsableFile()
  before attaching --cookies, so a missing/unusable projection fails closed instead of reusing stale bytes.

A projection failure may temporarily make valid Room credentials unavailable, but it does not preserve
revoked stale runtime authority.

Disposition:
BUG-COOKIE-01 = VERIFIED_CLOSED.

## BUG-COOKIE-04 — VERIFIED_OPEN P2

Historical root:
Chromium cookie expiry is persisted in a different epoch/unit from the Netscape cookie-file expiry.

Current exact source still reproduces it:
- CookieViewModel.getCookiesFromDB() reads expires_utc with getLong();
- that raw value is assigned directly to WebViewActivity.CookieItem.expiry;
- CookieItem.toNetscapeFormat() writes expiry unchanged as the Netscape expiry field;
- there is no Chromium Windows-epoch microseconds -> Unix seconds conversion and no explicit session
  cookie normalization.

Disposition:
BUG-COOKIE-04 = VERIFIED_OPEN P2.

Required direction:
convert Chromium expiry semantics before durable app/Netscape publication, preserve session-cookie 0,
and reject or safely handle unrepresentable values.

## BUG-COOKIE-05 — VERIFIED_OPEN P2

Historical root:
host-only Chromium cookie scope is broadened into a Netscape domain cookie.

Current exact source still reproduces it:
- getCookiesFromDB() prepends "." to every host_key that lacks one;
- it constructs WebViewActivity.CookieItem without overriding includeSubdomains;
- CookieItem defaults includeSubdomains=true and serializes that value as the second Netscape field.

Thus an exact-host browser cookie is still durably converted to subdomain-authorized runtime authority.

Disposition:
BUG-COOKIE-05 = VERIFIED_OPEN P2.

Required direction:
preserve the original host-only/domain-cookie distinction through extraction, durable app representation,
and Netscape serialization.

## BUG-COOKIE-06 — VERIFIED_OPEN P2

Historical root:
a fresh WebView authentication/no-auth session begins before asynchronous cookie clearance is acknowledged.

Current exact source still reproduces it in both reviewed activities:
- WebViewActivity on first creation calls CookieManager.removeAllCookies(null), immediately flushes, then
  composes a WebView whose AndroidView update can immediately load the target URL;
- PoTokenWebViewLoginActivity noAuth mode does the same removeAllCookies(null) + flush sequence before
  its WebView begins loading;
- neither path supplies a ValueCallback or otherwise waits for removal completion before first navigation.

Disposition:
BUG-COOKIE-06 = VERIFIED_OPEN P2.

Required direction:
make cookie-clear completion an awaited admission barrier before the first WebView request that is intended
to be fresh/unauthenticated.

## BUG-COOKIE-07 — VERIFIED_OPEN P2

Historical root:
partitioned Chromium cookie identity is flattened into an ordinary Netscape cookie identity.

Current exact source still reproduces it:
- CookieViewModel's Chromium projection reads only host_key, expires_utc, path, name, value, and is_secure;
- no partition/top-frame-site key is read, filtered, represented, or rejected;
- the seven-field Netscape carrier cannot reconstruct partition authority later;
- the shared runtime projection therefore cannot distinguish same host/name/path values belonging to
  different top-level partitions.

Disposition:
BUG-COOKIE-07 = VERIFIED_OPEN P2.

Required direction:
do not silently project partitioned credentials into unpartitioned Netscape authority; either preserve an
equivalent supported authority or fail closed/filter them with an explicit product contract.

## BUG-COOKIE-08 — VERIFIED_OPEN P2

Historical root:
multiple enabled cookie rows with the same Netscape identity can make an older credential win over a newer
refresh because row order and cookie-jar last-write semantics disagree.

Current exact source still reproduces the ordering:
- CookieDao.getAllEnabledCookies() returns enabled rows ORDER BY id DESC, newest first;
- CookieProjectionCoordinator appends each row's cookie lines in that order;
- it only removes byte-identical duplicate lines with distinct();
- two lines with the same semantic cookie identity but different values both survive;
- the older row is therefore later in the generated file and can replace the newer value in a consumer
  jar whose identity is domain/path/name.

Disposition:
BUG-COOKIE-08 = VERIFIED_OPEN P2.

Required direction:
resolve collisions by parsed exact cookie identity with an explicit newest/current-authority winner before
writing the runtime jar.

## BUG-COOKIE-09 — VERIFIED_CLOSED

Historical root:
projection used StringBuilder.contains(line), so a valid cookie line could be dropped merely because its
bytes appeared as a substring of a different line.

Current exact source:
- CookieProjectionCoordinator parses row content into individual lines;
- comments/blanks are filtered;
- the projection uses cookieLines.distinct(), which compares complete line strings;
- raw substring containment is no longer used as the deduplication rule.

This closes the recorded substring-identity omission root. It does not close BUG-COOKIE-08, because exact
but value-different semantic duplicates still need identity-aware precedence.

Disposition:
BUG-COOKIE-09 = VERIFIED_CLOSED.

## Immediate audit result

new_candidate_ids_audited_here: 7
verified_open_here: 5
verified_closed_here: 2

Corrected cumulative lower-bound progress:
- candidate IDs: 136
- audited unique IDs: 88
- verified closed/currently not reproduced: 69
- verified open: 19
- not yet audited inside lower bound: 48

New verified-open non-canonical roots from this checkpoint:
- BUG-COOKIE-04 — P2
- BUG-COOKIE-05 — P2
- BUG-COOKIE-06 — P2
- BUG-COOKIE-07 — P2
- BUG-COOKIE-08 — P2

No production source, prompt, active implementation scope, Master Plan or ledger was changed.
