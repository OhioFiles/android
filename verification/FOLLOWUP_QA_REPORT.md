# You redesign and upload fix — QA result

**PASS for the exercised scope.** Audited 2026-10-29 on Redmi Note9Pro, Android 16/API 36. Final optimized **2.1 / code 3** is installed with the existing app data preserved.

## Final artifact

[OhioFiles-Android.apk](../OhioFiles-Android.apk), **2,408,137 bytes**.

SHA256: `516b7b174e7a0c507843cf2b3622b4c584508a8eabb814cf6c59bc30d82cbbed`.

Auditor independently hashed the installed base.apk and release artifact; both match. Implementer promoted those exact tested bytes to the stable APK without rebuilding. Updates used install -r; no uninstall, data clear, session regeneration or user-file retry occurred.

## Upload regression and recovery

- Production POST /upload reproduced **401** with a new private 1 MiB fixture when the file part preceded session/metadata. Sanitized response: `{"error":{"code":"ERROR","message":"A valid session key or login is required"}}`.
- Backend reads multipart fields immediately after req.file(), before consuming the file stream. Trailing fields can be unavailable; small earlier fixtures masked the defect through parser read-ahead.
- Identical **1,048,626-byte** control payload with metadata first/file last returned **200**. Server confirmed custom filename/slug, selected folder, private visibility, one-time flag, password protection and exact checksum.
- The old native app independently reproduced Upload failed (401) using another new private fixture. After installing the repair, the **exact failed manifest, staged bytes/checksum, captured options, stable slug and session identity** remained unchanged. One manual Retry completed successfully. Server contained exactly one matching file, including correct folder/custom name/private/one-time/password settings. Cold relaunch restored the completed result without a duplicate.
- The user's earlier failed queue was already empty when inspection began; recovery was verified with the new isolated failed queue. No claim is made that an unavailable historical queue was recovered.
- UI now distinguishes queued, preparing, transferring, finishing and waiting to retry. Bytes reaching completion leave an indeterminate Finishing state until server acknowledgement; retry states discard stale 100%. Unauthorized failures stop with guidance and keep the queue.

Evidence: [native failure](upload-order-native-failed.png), [repaired native success](upload-order-repaired-success.png).

## You screen

- New compact identity card, equal Edit/View actions, actual server storage figures and grouped account actions. No session key or technical session ID appears on the landing screen.
- Manual direct journeys passed: Edit/Profile & status, View profile → correct own public profile, Connected devices → Devices, Backups → Backups, Appearance → Settings. Account tools have a single contextual toolbar.
- Read-only figures match session-insights after QA cleanup: **0 files, 1 folder, 0 B, 0 backups**. Original ProjectAssets preserved.
- Light and original Dynamic/AMOLED appearance visually passed. At font 1.3, buttons adapt without nickname/label clipping; landscape scroll reaches every management and recovery action.
- Offline profile retains identity and cached figures with an explanatory warning. Reconnect + Refresh clears it. Recovery opens masked, advanced destructive actions are hidden initially, and dismissal works.
- Session identity fingerprint stayed unchanged. Final preferences restored to the user's **DYNAMIC / AMOLED / ROUND**, font 1.0, portrait rotation 0, auto-rotation 0, Wi-Fi on, mobile data off.

Screenshots: [final original appearance](you-final-amoled.png), [light](you-remade-light.png), [font 1.3 portrait](you-font-130-portrait.png), [landscape reachable actions](you-font-130-landscape-actions.png). Screenshots were captured and immediately visually inspected. No revealed keys or pairing QR codes were captured.

## Automated checks and optimized runtime

- **14 unit tests pass, 0 failures/errors**, independently inspected from Gradle XML: 7 existing contracts, 5 upload regressions, 2 account-insights parsing tests.
- Upload tests include a 2 MiB multipart-order contract, 1 MiB text destination ordering, nested 401 parsing, and the real shared sendUpload request with delayed success/error responses. All bytes can be sent while the operation remains incomplete until server acknowledgement.
- **1 targeted physical-device Compose navigation component test passes** on a matching final-source debug/test APK pair. Actual You journeys were exercised manually. [Transcript](followup-instrumentation-results.txt). The earlier 5-test crypto/queue suite was not repeated because those implementations were unchanged.
- Lint: **0 errors, 51 warnings**. Categories remain dependency/toolchain notices, KTX/version-catalog suggestions, unused resources, locale-dependent size labels, intentional durable-pointer commits, data-extraction configuration and application-Context storage. No new material lint defect was found.
- R8 optimized release build succeeds. Cold Activity start: **487 ms**. Short warmed navigation sample: **451 frames, 6 missed-deadline frames (1.33%)**, p50 19 ms, p90/p95 20 ms, p99 48 ms, GPU p95 14 ms. [Raw sample](followup-release-frames.txt).
- Current final-release process had **0 fatal/ANR markers** in available PID-scoped logcat. [Stability check](followup-release-stability.txt).

The frame sample is a small physical-phone observation, not sustained FPS or a cross-device benchmark; the logcat check is not a soak test.

## Cleanup and coverage limits

Removed only follow-up QA cloud files/folder and created phone Download fixture/directory. Cleared the completed QA upload queue through the app; staged queue directory returned to empty. Existing user files, profile, devices and backups were untouched. Prior audit's documented encrypted self-only QA message remains unchanged.

No real profile edits, device removal, session import/renew/revoke, backup import/restore, pairing-secret reveal or public/multiuser mutations were performed. Large-transfer/background restrictions retain the previously documented limits; this follow-up verified a private 1 MiB transfer and precise failed-queue recovery.
