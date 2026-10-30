# Android web-parity verification matrix — in progress

Scope: native equivalents approved by Planner; browser CSS, site publishing/domains, embeds/PWA, CLI/admin and desktop window chrome intentionally excluded. This is not a full-parity pass.

## Core phase result — 2026-10-30

**PASS limited core chunk journey; full feature acceptance remains pending.**

Patched debug SHA256: `fa9898f76f72cf52186768ed5b9ef5acdcc42e5a74cc65f57d073ce71800f396`. Installed with `-r`; no uninstall/data clear. Independent updated XML inspection: **36 JVM tests, 0 failures, 0 errors, 8 classes**. No competing Gradle build was run by Auditor.

- The exact failed private 25,171,968-byte queue, checksum/options/session and six-character slug `teMres` survived the update. Retrying it fixed the production init 400.
- Native worker created five chunks of 5,242,880 bytes (last shorter). Durable journal recorded acknowledged set `[4]`.
- WiFi was disabled and PID `24495` killed with SIGKILL, not force-stop. Re-reading disk retained `[4]` and the same slug/upload session. No server file existed before finalization.
- Android restarted the process as PID `25316` while offline. After WiFi reconnect, with no Activity launch until completion, observed journal sets were `[4]` → `[4,0,1]` → `[4,0,1,2]` → `[4,0,1,2,3]`.
- Server returned exactly **one** owned, private file with slug `teMres`, 25,171,968 bytes, SHA256 `f5782f80d960f23cc80cc07f22c7d3cf49552bc0f3f75bc853c5b67c1779ba86`. Streaming its download independently produced the same byte count and hash. Native UI showed **Upload Complete**.
- **Evidence limit:** the production API has no exposed received-chunk/index counters, and production append requests were not traced. The physical journey proves persisted acknowledgement/resume and unique correct final file, not direct wire proof that index4 was never repeated. The separate request-tracker unit test executes the same `uploadChunked` function and verifies acknowledged indexes are not posted, exact remaining ranges, mandatory auth, lost append/finalize acknowledgement and expiry/auth behavior.
- App crash buffer scoped to resumed PID contained no entries.
- Screenshot `chunk-native-resumed-success.png` captured and visually inspected; prior failure screenshot retained.
- **Cleanup complete:** only own QA server ID `cmvupetx001tengrxw3xpa1ke` was deleted; slug lookup empty. Native completed queue cleared through Upload Another File; upload-queue files empty. Only own MediaStore row1091/phone fixture removed.
- Fresh after-cleanup snapshot matches before: **0 pre-existing files, 1 folder, same folder URLs, same session fingerprint and DYNAMIC/AMOLED/ROUND preferences**. WiFi1/mobile-data0 restored. No font/rotation/lock settings changed; existing USB-awake15 and timeout600000 untouched.

### Source follow-ups, not final verified

- Main reports bulk hidden-row selection, immutable target snapshot, incremental/cancel-safe Undo and per-transaction Undo isolation repaired. Static reread confirms unified visible row derivation and incremental/finally structure; final isolated device regressions pending.
- Native notebook bounds/export uniqueness/regex materialization follow-ups sent owner. Imported JSON source clears remote links and remaps IDs; explicit regression/physical roundtrip pending.
- Per-file options combinations, folder setting propagation/permissions, bulk SAF ZIP, all editor/docs/viewers, social/chat/voice, insights/revoke-isolated, notifications/routes, final accessibility/performance and optimized release installation remain future phases. No all-features pass.


## Baseline and preservation

- Last completed release: 2.2/code4, SHA256 `1af9bd853acb03c68f93afaa8f12eb62558fe6883a47bead8d767f18fdb866a7`.
- Current first integration debug installed with data preserved: SHA256 `a0b92dabb2f987800f9c43315834579464c83dd5aeab25416d28bccd7b749900`.
- Fresh authenticated baseline: 0 files, 1 folder; session fingerprint prefix `42be614af18c`; DYNAMIC/AMOLED/ROUND. No pre-existing content altered.
- Device is Android16/API36 Redmi Note9Pro. No uninstall or data clear.
- Independent first-integration XML inspection: 34 JVM tests, 0 failures, 0 errors in 8 classes. These are protocol/component checks, not exhaustive integration proof.

## Initial findings (see core result above for repaired init)

1. **FAIL — production chunk init**: native 25,171,968-byte SAF upload, default private/password unset, returns 400 “Invalid request data” before a chunk journal is written. Init sent `password:null`; backend password schema is optional string, not nullable. Main reports source repaired; updated APK/retry pending. Screenshot `chunk-native-init-failure.png` captured and visually inspected.
2. **FAIL — bulk filtering scope**: Select all originally ignored folder search/shared and file type/shared filters, allowing hidden rows to be included. Main reports unified visible row sets and immutable operation snapshots repaired; updated APK and isolated fixture test pending.
3. **FAIL — interrupted bulk Undo**: deletion Undo IDs were published only after entire UI-owned coroutine finished. Main reports incremental checkpoints/final refresh and partial Undo bookkeeping repaired; verification pending.
4. **PENDING — notebook import bounds**: ignored ZIP entries could decompress outside accepted-text byte budget. Sent to Native owner. Also requested bounded regex match materialization/replacement output and deterministic unique exported filenames.
5. **PASS source / PENDING fixture**: imported notebook JSON resets document links and IDs; local store restore retains own links separately. Requested explicit linked-export/import regression.

## Acceptance rows

| Feature | New source/contract | Current evidence and next gate |
|---|---|---|
| Folder settings, visibility cascade, password keep/set/remove/reveal, slug/type/access | FilesViewModel, FolderPropertiesDialog, OhioApiClient; backend folders PATCH/check-slug/password | Source present. Need disposable nested-folder roundtrip, propagation, conflict/error, cancel, owner/EDIT/VIEW gate. No existing URL mutation. |
| Bulk select/move/delete/visibility/ZIP and Undo | FilesScreen/FilesViewModel, batch-move and batch-download-zip | Scope and cancellation defects above. Need filtered fixtures, mixed permissions, immutable IDs, partial errors, Undo, streamed SAF export. |
| Folder sorting | sortedFolders by date/name/item count | Source present; actual order and search scope pending. |
| Durable chunks and per-file options | ChunkedUpload, UploadWorker/Queue, options dialog; init/append/finalize/abort | 4 protocol tests pass; native init FAIL above. Need same queue retry, partial acknowledged checkpoint, PID kill/restart, missing offsets only, checksum/owner/private/6-char slug once, lost-ack protocol, flags and per-item metadata. |
| Existing queue/custom links/session/theme | additive manifest fields + UploadSlug reconciliation | Prior 2.2 tests pass; current snapshot preserved. Final compare required. |
| Deep routes | notifications/workspace/doc/group/draft post/file | Main implementation in progress; cold/warm/config state and actual destinations pending. |
| Android notifications | foreground/inbox + real push transport or proven unavailable truthful fallback | Main implementation in progress. Must document scheduler latency; no instant-delivery claim without observed transport. |
| Editor/notebook | document tools, NativeNotebook, NotebookStore | Find/replace/format/syntax/import/export/publish sources present; 4 document tests pass. Need private cloud conflict, local draft/process death, import unlink/bounds, export roundtrip and adaptive keyboard. |
| Docs and rich Markdown | NativeDocsScreen, NativeMarkdown | Hierarchy/search/TOC/config/custom AST source present; parser test passes. Need unknown config preservation, image/task/nesting/custom fixture, bounds and actual navigation. |
| Rich media and structured previews | image/PDF/CSV/diff/archive/3D | Native owner phase pending. Need format fixtures and bounded IO/GPU/temp disposal; do not infer done from compile. |
| Community discovery/full posts/public folders/composer preview | CommunityContracts/ViewModel/Screen/SocialDetail | Global public files omit session, pagination and full-detail endpoint source confirmed; 4 social tests pass. Actual read-only >100/global results and long rendered post pending. No public posting/reactions/comments authorized for QA. |
| Chat stickers/reactions/prefill preview | Native chat/marker support | First phase source present; exact web wire fixture and actual encrypted self-only roundtrip pending. |
| Chat inline recording/playback/phone attachment/post composition | Native owner social phase | Supporting sources not yet fully wired. Pending, not complete. Preserve existing E2EE text protocol; do not label media bytes E2EE unless proven. |
| Rich insights/current-device/revoke others | account source first phase | Source present; actual read-only stats matching pending. Revoke only isolated disposable identity; no real other-device changes. |
| Accessibility/motion/performance | every affected surface | Final integration font1.3 portrait/landscape, dark/current preference, keyboard/cancel, reduced motion and optimized-release frame sample pending. |

## Initial private QA fixture (now cleaned)

Only own new file: `Auditor-QA-chunk-resume-20261030.data`, 25,171,968 bytes, SHA256 `f5782f80d960f23cc80cc07f22c7d3cf49552bc0f3f75bc853c5b67c1779ba86`.
Initially pending durable batch `b7e7c3f1-a5a5-4753-8f0a-308383dd203f`, item `38474600-cf4d-487f-bbad-b82f485dc33b`, generated slug `teMres`. No server file created on failed init. The repaired retry used these same staged bytes/options/link; server file, native queue and phone source are now cleaned as recorded above.
