# Email Manager — Performance Implementation Plan

**Audience:** backend engineer
**Scope:** backend (Spring Boot) + minor Flutter client changes
**Goal:** make loading, sync, and notifications fast and stable when the user has up to ~20 connected Gmail accounts.

---

## 1. Problem statement

All three symptoms — slow loading, slow updates, laggy notifications — share one root cause:

> **IMAP is on the request path and the notification path. Nothing is cached locally.**

Today every screen triggers a live IMAP session:

- `EmailFetchService.fetchEmails()` opens a folder and fetches envelopes on every request.
- `EmailFetchService.listFolders()` opens **every** folder `READ_ONLY` just to read unread/total counts — dozens of round-trips per account.
- `EmailPollingScheduler` polls all accounts every 15s with a 5-thread pool and `allOf().join()`, so the cycle waits for the slowest account. One hung account burns the 20s lock timeout in `ImapConnectionService` and stalls the whole cycle.
- "What's new" state (`lastChecked`) is an in-memory `ConcurrentHashMap`, so it resets on restart → missed or duplicate notifications after every deploy.

The fix is a **cache-first architecture**: a local Postgres mirror of email metadata that the app reads instantly, kept up to date by a background sync that is decoupled from user requests, plus IMAP IDLE for near-instant notifications.

### Goals
- App reads (folder list, email list) served from the local DB in <100ms, never blocking on IMAP.
- New mail surfaces in seconds, not tens of seconds, regardless of account count.
- Notification state survives restarts (no missed/duplicate pushes).
- One slow/broken account never degrades the others.

### Non-goals (for this work)
- OAuth / Gmail API migration (separate, larger effort — noted in §10).
- Full offline body caching (we cache metadata + lazy-load bodies).
- Multi-user/tenant concerns (remains a single-user personal tool).

---

## 2. Target architecture

```
                    ┌─────────────────────────────┐
   Flutter app ───► │  REST controllers           │  reads ONLY from Postgres
                    │  (EmailController etc.)      │  → instant, no IMAP in request path
                    └──────────────┬──────────────┘
                                   │ read/write
                                   ▼
                    ┌─────────────────────────────┐
                    │  Postgres cache              │
                    │  cached_email, folder_state, │
                    │  account_sync_state          │
                    └──────────────▲──────────────┘
                                   │ writes
                    ┌──────────────┴──────────────┐
                    │  SyncService (background)    │  IMAP I/O lives here only
                    │   • incremental UID sync     │
                    │   • flag reconciliation      │
                    │   • IDLE listeners per acct  │
                    └──────────────▲──────────────┘
                                   │ push on new UID
                                   ▼
                          PushNotificationService (FCM)
```

Key principle: **controllers never touch IMAP for reads.** IMAP is only used by (a) the background sync, (b) write actions (send, mark read, move, delete) which are then reflected into the cache.

---

## 3. Phase 1 — Local cache (highest impact, do this first)

### 3.1 New tables

Use Flyway (already a dependency — currently disabled). Enable it and add migrations under `src/main/resources/db/migration/`. Stop using `ddl-auto: update` in prod.

```sql
-- V1__cache.sql

CREATE TABLE folder_state (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL REFERENCES email_accounts(id) ON DELETE CASCADE,
    full_name       VARCHAR(512) NOT NULL,   -- IMAP full folder name
    display_name    VARCHAR(255) NOT NULL,
    uid_validity    BIGINT,                  -- IMAP UIDVALIDITY for this folder
    last_seen_uid   BIGINT NOT NULL DEFAULT 0, -- high-water mark for incremental sync
    unread_count    INT NOT NULL DEFAULT 0,
    total_count     INT NOT NULL DEFAULT 0,
    last_synced_at  TIMESTAMP,
    UNIQUE (account_id, full_name)
);

CREATE TABLE cached_email (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL REFERENCES email_accounts(id) ON DELETE CASCADE,
    folder          VARCHAR(512) NOT NULL,
    uid             BIGINT NOT NULL,          -- IMAP UID (stable within a UIDVALIDITY)
    message_id      VARCHAR(512),             -- RFC Message-ID header (cross-folder identity)
    subject         TEXT,
    from_address    VARCHAR(512),
    from_name       VARCHAR(512),
    snippet         TEXT,
    received_at     TIMESTAMP,
    seen            BOOLEAN NOT NULL DEFAULT FALSE,
    has_attachment  BOOLEAN NOT NULL DEFAULT FALSE,
    body_text       TEXT,                     -- nullable; filled lazily on first open
    body_html       TEXT,                     -- nullable; filled lazily on first open
    body_loaded     BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (account_id, folder, uid)
);

CREATE INDEX idx_cached_email_list
    ON cached_email (account_id, folder, received_at DESC);

CREATE TABLE account_sync_state (
    account_id        UUID PRIMARY KEY REFERENCES email_accounts(id) ON DELETE CASCADE,
    last_full_sync_at TIMESTAMP,
    last_notified_uid BIGINT NOT NULL DEFAULT 0, -- persistent notification high-water mark (INBOX)
    sync_status       VARCHAR(32) NOT NULL DEFAULT 'IDLE', -- IDLE | SYNCING | ERROR
    last_error        TEXT
);
```

> Note: store **UID**, not JavaMail message sequence numbers. Sequence numbers shift when messages are deleted; UIDs are stable for the lifetime of a `UIDVALIDITY`.

### 3.2 New JPA entities / repositories
- `CachedEmail` entity + `CachedEmailRepository extends JpaRepository<CachedEmail, UUID>` with:
  - `Page<CachedEmail> findByAccountIdAndFolderOrderByReceivedAtDesc(UUID, String, Pageable)`
  - `Optional<CachedEmail> findByAccountIdAndFolderAndUid(UUID, String, long)`
  - `int countByAccountIdAndFolderAndSeenFalse(UUID, String)`
- `FolderState` entity + repository.
- `AccountSyncState` entity + repository.

### 3.3 Controllers read from cache
Rewrite the read paths in `EmailController` to hit the new repositories instead of `EmailFetchService`:

- `GET /folders` → return `FolderState` rows for the account (no IMAP).
- `GET /emails?folder=&page=&pageSize=` → `CachedEmailRepository` page query (no IMAP). Keep the existing `EmailPageDto` shape so the Flutter client doesn't change.
- `GET /emails/{uid}` → return from `cached_email`; if `body_loaded = false`, **this one endpoint** does a single lazy IMAP fetch for the full body, writes it back, sets `body_loaded = true`, and returns. (Bodies are big; we don't sync them in bulk.)

`EmailFetchService` stays, but becomes an **internal** dependency of the sync service, not something controllers call directly.

### 3.4 Write actions update the cache
`EmailActionService` (mark read/unread, move, delete, archive) and `EmailSendService` already perform the IMAP operation. After a successful IMAP op, **also update the corresponding `cached_email` row** (e.g. set `seen = true`, or delete the row on move/delete) so the UI reflects the change immediately without waiting for the next sync. This is "optimistic write-through."

### 3.5 Expected outcome of Phase 1
Loading becomes instant because reads are local. Lock contention between user requests and the poller disappears (controllers no longer acquire IMAP locks). This alone resolves the "loading time" complaint.

---

## 4. Phase 2 — Incremental UID sync (fixes update time)

Replace the "re-fetch a page every time" model with "fetch only what changed."

### 4.1 New `SyncService`
One method, `syncAccountFolder(account, folderName)`:

1. `acquireStore` → open folder `READ_ONLY`.
2. Read the folder's current `UIDVALIDITY`.
   - If it **differs** from `folder_state.uid_validity` (or no row exists): the server's UID space reset → wipe `cached_email` for that folder, reset `last_seen_uid = 0`, and do a bounded initial sync (most recent N, e.g. 50).
3. **New messages:** `IMAPFolder.getMessagesByUID(lastSeenUid + 1, UIDFolder.LASTUID)`. Batch-fetch ENVELOPE + FLAGS + UID + CONTENT_INFO (reuse the existing `FetchProfile` logic in `EmailFetchService.fetchEmails`). Upsert into `cached_email`. Advance `last_seen_uid` to the max UID seen.
4. **Flag changes (read/unread):** fetch FLAGS for the most recent window (e.g. last 200 UIDs, or all cached UIDs for small folders) and reconcile the `seen` column. This is cheap — flags only, no bodies.
5. **Deletions:** compare the set of UIDs the server returns for the recent window against cached UIDs in that window; delete cache rows whose UID no longer exists server-side.
6. Recompute `unread_count` / `total_count`, update `folder_state.last_synced_at`.

> Snippet bodies: the current code sets `snippet = ""` to stay fast. With the cache you can afford a small `TEXT` partial fetch per *new* message during sync (not on every list load), so the list finally shows real previews. Keep it bounded (first ~200 chars, plain-text part only).

### 4.2 Scheduling — per-account, independent
Replace the single `EmailPollingScheduler.pollAllAccounts()` cycle (which waits for the slowest account) with **independent per-account scheduling** so one slow account can't stall others:

- Submit each account's sync as its own task on a pool (see §5 re: virtual threads).
- Drop `allOf().join()`. Each account syncs on its own cadence; failures are isolated and recorded in `account_sync_state.sync_status = ERROR`.
- For folders other than INBOX, sync on demand (when the user opens that folder) or on a slower cadence. Only INBOX needs aggressive freshness.

### 4.3 Expected outcome of Phase 2
Sync work per cycle drops from "re-fetch pages for every account" to "fetch only new UIDs + a flag delta." Even 20 accounts stay cheap, and a single broken account no longer degrades the rest.

---

## 5. Phase 3 — IMAP IDLE + virtual threads (fixes notification latency)

Polling every 15s is the wrong tool for "notify me instantly." IMAP IDLE lets the server push you the moment mail arrives.

### 5.1 Move to Java 21 + virtual threads
- Bump `<java.version>` to 21 in `pom.xml` (Spring Boot 3.4 supports it).
- Hold one long-lived IDLE connection per account. With **virtual threads**, 20 blocking IMAP connections cost almost nothing, which retires the entire `MAX_CONCURRENT = 5` workaround and the semaphore in `EmailPollingScheduler`.

### 5.2 `IdleManager`
- On startup (and when an account is added/removed), start one virtual-thread task per active account that:
  1. Opens a **dedicated** IMAP connection for INBOX (separate from the request/sync connection — IDLE monopolizes its connection).
  2. Calls `IMAPFolder.idle()` in a loop.
  3. On a new-message event, triggers `SyncService.syncAccountFolder(account, "INBOX")`, then for any newly-inserted row with `uid > account_sync_state.last_notified_uid`, sends an FCM push and advances `last_notified_uid` (persisted — fixes the restart dedup bug).
- **Re-IDLE every ~28 minutes** (servers drop IDLE around 29 min) and reconnect with exponential backoff on failure.
- Keep a lightweight **polling fallback** (e.g. every 5 min) for accounts whose IDLE connection is currently down, so notifications degrade gracefully rather than stop.

### 5.3 Notification dedup
All dedup now keys off `account_sync_state.last_notified_uid` in Postgres instead of the in-memory `lastChecked` map. Delete the in-memory map. Restarts and redeploys no longer miss or replay notifications.

### 5.4 Expected outcome of Phase 3
Notification latency goes from "up to a full poll cycle (tens of seconds at 20 accounts)" to "a second or two after Gmail receives the mail," with correct dedup across restarts.

---

## 6. Cross-cutting changes

- **Connection roles:** separate the IDLE connection from the sync/action connection per account. IDLE holds its connection; reusing it for fetches causes "folder busy" errors.
- **Config:** add tunables to `application.yml` — sync interval, IDLE re-issue interval, initial-sync page size, body-fetch toggle.
- **Failure isolation:** every per-account task wraps in try/catch, writes `sync_status`/`last_error`, and never propagates to other accounts.
- **Flyway:** enable it; set `ddl-auto: validate` (not `update`) in prod so migrations are the single source of truth.
- **Flutter:** minimal change. Because list/folder DTO shapes are preserved, the main client change is optional — a pull-to-refresh that calls a new `POST /accounts/{id}/sync` endpoint to force an immediate sync, and trusting the cache for instant loads.

---

## 7. Rollout order

1. **Phase 1** behind everything: add tables (Flyway), entities, repos. Add a sync that simply populates the cache on a timer (can temporarily reuse current full-fetch logic). Switch controllers to read from cache. **Ship — loading is now fast.**
2. **Phase 2**: replace the populate-by-full-fetch with incremental UID sync + per-account scheduling. **Ship — updates are now cheap.**
3. **Phase 3**: Java 21 + `IdleManager` + persistent notification dedup; demote polling to fallback. **Ship — notifications are now instant and reliable.**

Each phase is independently shippable and independently improves a symptom.

---

## 8. Risks & edge cases

- **UIDVALIDITY changes** (rare, but real): must wipe + re-sync the folder, or UIDs point at the wrong messages. Handle explicitly (§4.1 step 2).
- **Gmail labels vs folders:** Gmail exposes labels as IMAP folders and a message can appear in several. Use `message_id` for cross-folder identity if you later dedup across folders; for now, cache per (account, folder, uid).
- **IDLE connection limits:** Gmail allows up to ~15 simultaneous IMAP connections per account. We use 1 IDLE + occasional sync per account, well within limits.
- **App password fragility / OAuth:** the whole IMAP foundation depends on Gmail app passwords, which Google is phasing down. None of this work is wasted by a future OAuth move (the cache and sync model are identical; only the connection auth changes), but flag it as the next strategic project.
- **Clock/timezone:** current code uses `ZoneId.systemDefault()`. Store `received_at` in UTC to avoid drift.
- **Large bodies:** never bulk-sync bodies; lazy-load on open (§3.3). Consider a size cap.

---

## 9. Testing checklist

- Cold start with empty cache → initial sync populates INBOX; list loads from DB.
- New mail arrives → IDLE fires → row inserted → exactly one FCM push; `last_notified_uid` advances.
- Restart the backend → no duplicate or missed notifications for mail that arrived while down.
- Mark read in app → cache updated immediately AND reflected on Gmail; next sync doesn't revert it.
- Delete/move in app → row removed from cache; survives next sync.
- Kill one account's connection (bad password) → that account shows `ERROR`, others keep syncing and notifying.
- Force `UIDVALIDITY` change (test against a folder rename/recreate) → cache wipes and rebuilds cleanly.
- 20 accounts connected → measure list-load latency (should be DB-bound, <100ms) and notification latency (target a few seconds).

---

## 10. Effort estimate (rough, one engineer)

- Phase 1 (cache + read switch + write-through): ~3–5 days.
- Phase 2 (incremental sync + per-account scheduling): ~3–4 days.
- Phase 3 (Java 21 + IDLE + persistent dedup): ~3–5 days.
- Buffer for edge cases/testing: ~2–3 days.

**Total: ~2–3 weeks** for a robust result. Phase 1 alone (a few days) removes the most visible pain.

---

## Appendix — files touched

| Area | Existing file | Change |
|------|---------------|--------|
| Schema | `resources/db/migration/` (new) | Add Flyway migrations; enable Flyway |
| Entities | new `cache/` package | `CachedEmail`, `FolderState`, `AccountSyncState` + repos |
| Reads | `emails/controller/EmailController.java` | Serve folders/list/detail from cache |
| Sync | new `sync/SyncService.java` | Incremental UID sync, flag/deletion reconcile |
| Fetch | `emails/service/EmailFetchService.java` | Becomes internal helper for SyncService; add bounded snippet fetch |
| Actions | `emails/service/EmailActionService.java`, `EmailSendService.java` | Write-through cache updates |
| Notifications | `notifications/EmailPollingScheduler.java` | Replace with per-account sync triggers + polling fallback |
| IDLE | new `notifications/IdleManager.java` | Per-account IDLE on virtual threads |
| Dedup | `notifications/PushNotificationService.java` | Key dedup off persistent `last_notified_uid` |
| Build | `pom.xml`, `application.yml` | Java 21; Flyway enabled; `ddl-auto: validate`; sync/IDLE tunables |
```
