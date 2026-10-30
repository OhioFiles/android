# Six-character upload slug verification — 2.2

**PASS**, 2026-10-29, Redmi Note9Pro / Android16 API36.

The old Android queue generated a 32-character UUID and passed it as customSlug, bypassing the backend/web default. New automatic slugs now use the same six-character alphanumeric alphabet as the web. Existing queued/custom slugs remain unchanged.

## Verification

- Independently inspected **21 unit tests: 0 failures/errors**. Seven slug regressions cover alphabet/reserved routes, legacy manifest preservation, explicit custom links, preflight collisions, foreign/owned same-content collisions, 409 acknowledgement reconciliation, uncertain network/server/cancellation failures, and the bounded ten-candidate policy.
- **2 targeted physical-device queue regression tests pass**, including atomic rotated-manifest persistence across repository restart, preserved source/options/custom links, rejection of a stale previous checkpoint without overwrite, and the prior incoming-share queue ownership regression. [Transcript](slug-instrumentation-results.txt).
- Lint: **0 errors, 51 existing warnings**. Optimized R8 release build passes.
- Actual new private native upload: **1,048,635 bytes**, SHA256 `2e72c4dbacbac8de0f9fdc14ba9ac632f2ae88527e36ec938bfd385fc6c8a37c`. No custom slug supplied. Offline staging persisted **JtVMR9**, isAutoSlug=true, before any POST.
- Killed app PID27767 while queued offline. After reconnect, Android started PID28125 without opening an Activity; the upload completed using the **same six-character JtVMR9**, exactly one server file, correct size/checksum and private visibility. Cold UI restore displayed the completed short link.
- Existing user file ID→32-character URL mapping, session identity fingerprint, server and DYNAMIC/AMOLED/ROUND settings stayed unchanged. No existing URL was renamed, and there was no pre-existing queued upload at baseline.
- Removed only the newly created QA server file and phone Download fixture; cleared its completed queue through the app. Queue directory is empty again, and the original user file remains.

[Observed successful short link](slug-six-character-success.png), captured and immediately visually inspected.

## Final artifact

Optimized version **2.2 / code4**, **2,408,137 bytes**.

SHA256 `1af9bd853acb03c68f93afaa8f12eb62558fe6883a47bead8d767f18fdb866a7`.

Auditor independently hashed the installed base.apk and release artifact; they match. Stable [OhioFiles-Android.apk](../OhioFiles-Android.apk) is promoted from the exact tested release bytes without rebuilding. Update used install-r, with no uninstall or data clear. Cold Activity launch486ms; current PID-scoped logcat had zero fatal/ANR markers in its available buffer. No new broad performance benchmark or unrelated feature testing was performed.
