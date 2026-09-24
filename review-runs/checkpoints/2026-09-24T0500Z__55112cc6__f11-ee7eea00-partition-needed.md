# F11 ee7eea00 partitioned broad verification required

Local candidate `ee7eea001462b77e88a201ed2f26c2385048d421` reported focused WorkManagerHandoff semantic PASS 13/13.

The following monolithic 17-class run did not establish the broad semantic gate. `F11PreferenceMutationAdmissionProductionWiringTest` stopped in its setup readiness check with `spl=0` and `restoreGate=false`, before its intended preference/Restore semantic scenario was established.

Disposition: `BROAD_EXECUTION_HARNESS_ISOLATION_BLOCKED`.

No new production defect is established from this setup failure. Canonical defect delta: 0.

Next execution: reuse the reconciled 17-class / 316-identity manifest and run a fresh Gradle/UTP invocation per class, using the exact ARM64 artifacts and unchanged candidate. Preserve per-class results and stop on the first valid semantic failure. If all 17 class invocations account for all 316 identities without a new unexplained semantic failure, reconcile the frozen baseline failures and proceed to the existing final remote-tip check and exact-candidate push gate.

CLEAN basis remains `90afaec157607669ea32fa41877e7f0efcdcca86`.

INDEPENDENT EXECUTION: NOT EXECUTED
