# API Contract Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the frontend checked-in OpenAPI reference match the backend runtime `/v3/api-docs`, and remove the stale `/api/admin/*` and `pending/count` documentation.

**Architecture:** Treat the backend `dev` runtime OpenAPI as the contract source of truth. First restore normal Flyway-enabled backend startup, then capture runtime OpenAPI and copy it into the frontend repo. Verification is command-driven and evidence is saved under `logs/`.

**Tech Stack:** Spring Boot, Flyway, PostgreSQL, OpenAPI JSON, Vue frontend repository, `curl`, `jq`, `comm`, Maven.

**Required environment variables:** keep credentials out of this file and export them locally before running the database repair steps.

```bash
export BLOG_V2_DB_URL='postgresql://<user>:<password>@<host>:<port>/<database>'
export BLOG_V2_FLYWAY_URL='jdbc:postgresql://<host>:<port>/<database>'
export BLOG_V2_DB_USER='<user>'
export BLOG_V2_DB_PASSWORD='<password>'
```

---

## File Structure

- Backend evidence logs, ignored by git:
  - `logs/api-contract-align-flyway-red.log`
  - `logs/api-contract-align-flyway-history-before.tsv`
  - `logs/api-contract-align-flyway-repair.log`
  - `logs/api-contract-align-backend-dev.log`
  - `logs/api-contract-align-health.json`
  - `logs/api-contract-align-openapi-runtime.json`
  - `logs/api-contract-align-runtime-endpoints.txt`
  - `logs/api-contract-align-frontend-endpoints.txt`
  - `logs/api-contract-align-frontend-only.txt`
  - `logs/api-contract-align-runtime-only.txt`
- Backend docs:
  - Modify: `docs/api-contract/gap-report.md`
- Frontend repository:
  - Modify: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/api-reference/openapi.json`

## Task 1: Reproduce Flyway-Enabled Dev Startup Failure

**Files:**
- Create evidence: `logs/api-contract-align-flyway-red.log`

- [ ] **Step 1: Run normal dev startup and save output**

```bash
mkdir -p logs
java -jar blog-start/target/blog-start-1.0.jar --spring.profiles.active=dev 2>&1 | tee logs/api-contract-align-flyway-red.log
```

Expected: process exits before successful startup with `Migration checksum mismatch for migration version 13`.

- [ ] **Step 2: Confirm the red evidence**

```bash
rg -n "Migration checksum mismatch|version 13|Successfully started|Started BlogWebV2Application" logs/api-contract-align-flyway-red.log
```

Expected: output includes `Migration checksum mismatch` and `version 13`; output does not include a successful `Started BlogWebV2Application` line after the error.

- [ ] **Step 3: Stop any accidental backend process**

```bash
if lsof -ti:9010 >/tmp/blog-v2-9010.pid; then kill "$(cat /tmp/blog-v2-9010.pid)"; fi
```

Expected: no backend remains listening on port `9010`.

## Task 2: Inspect And Repair Local Dev Flyway Checksum

**Files:**
- Create evidence: `logs/api-contract-align-flyway-history-before.tsv`
- Create evidence: `logs/api-contract-align-flyway-repair.log`

- [ ] **Step 1: Query current V13 Flyway history**

```bash
psql "$BLOG_V2_DB_URL" \
  -c "SELECT installed_rank, version, description, type, script, checksum, success, installed_on FROM flyway_schema_history WHERE version = '13';" \
  2>&1 | tee logs/api-contract-align-flyway-history-before.tsv
```

Expected: output shows one successful row for version `13` with checksum `-273628002`, matching the previous failure evidence.

- [ ] **Step 2: Verify V13 migration is already applied**

```bash
psql "$BLOG_V2_DB_URL" \
  -c "SELECT to_regclass('public.comment_likes') AS comment_likes_table, EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='comments' AND column_name='content_html') AS comments_has_content_html;" \
  2>&1 | tee logs/api-contract-align-v13-schema-check.tsv
```

Expected: `comment_likes_table` is `comment_likes` and `comments_has_content_html` is `t`. If either value is missing/false, stop and report that the database is not safely repairable by checksum-only repair.

- [ ] **Step 3: Repair Flyway schema history**

```bash
mvn -pl blog-start -am flyway:repair \
  -Dflyway.url="$BLOG_V2_FLYWAY_URL" \
  -Dflyway.user="$BLOG_V2_DB_USER" \
  -Dflyway.password="$BLOG_V2_DB_PASSWORD" \
  -Dflyway.locations=classpath:db/migration \
  2>&1 | tee logs/api-contract-align-flyway-repair.log
```

Expected: command exits `0` and output includes Flyway repair success text. If Maven reports no Flyway plugin goal, use the Spring Boot application only after confirming the project has no Maven Flyway plugin configuration and report the blocker.

- [ ] **Step 4: Query V13 Flyway history after repair**

```bash
psql "$BLOG_V2_DB_URL" \
  -c "SELECT installed_rank, version, description, type, script, checksum, success, installed_on FROM flyway_schema_history WHERE version = '13';" \
  2>&1 | tee logs/api-contract-align-flyway-history-after.tsv
```

Expected: version `13` is successful and checksum is no longer the stale value from the red failure.

## Task 3: Capture Flyway-Enabled Runtime OpenAPI

**Files:**
- Create evidence: `logs/api-contract-align-backend-dev.log`
- Create evidence: `logs/api-contract-align-health.json`
- Create evidence: `logs/api-contract-align-openapi-runtime.json`
- Create evidence: `logs/api-contract-align-runtime-endpoints.txt`

- [ ] **Step 1: Start backend with Flyway enabled**

```bash
java -jar blog-start/target/blog-start-1.0.jar --spring.profiles.active=dev 2>&1 | tee logs/api-contract-align-backend-dev.log
```

Expected: process keeps running and log includes `Started BlogWebV2Application`.

- [ ] **Step 2: In another shell, capture health**

```bash
curl -fsS http://localhost:9010/actuator/health | tee logs/api-contract-align-health.json
```

Expected: JSON contains `"status":"UP"`.

- [ ] **Step 3: Capture runtime OpenAPI**

```bash
curl -fsS http://localhost:9010/v3/api-docs | jq '.' | tee logs/api-contract-align-openapi-runtime.json
```

Expected: valid JSON with `.openapi`, `.info.title`, and `.paths`.

- [ ] **Step 4: Extract runtime endpoint list**

```bash
jq -r '.paths | to_entries[] as $p | $p.value | to_entries[] | "\(.key | ascii_upcase) \($p.key)"' \
  logs/api-contract-align-openapi-runtime.json \
  | sort \
  | tee logs/api-contract-align-runtime-endpoints.txt
```

Expected: output includes `GET /api/v1/admin/articles/pending` and does not include `GET /api/admin/articles/pending/count`.

- [ ] **Step 5: Stop backend**

```bash
kill "$(lsof -ti:9010)"
```

Expected: `curl -fsS http://localhost:9010/actuator/health` fails after shutdown.

## Task 4: Update Frontend OpenAPI Reference

**Files:**
- Modify: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/api-reference/openapi.json`

- [ ] **Step 1: Write the failing contract check before updating**

```bash
jq -r '.paths | keys[]' /mnt/d/end/workspace/vue/blog-web-v2-front-end/api-reference/openapi.json \
  | rg '^/api/admin/|pending/count' \
  2>&1 | tee logs/api-contract-align-frontend-stale-red.log
```

Expected: output includes stale `/api/admin/*` paths or `pending/count`.

- [ ] **Step 2: Replace frontend OpenAPI with runtime OpenAPI**

```bash
cp logs/api-contract-align-openapi-runtime.json /mnt/d/end/workspace/vue/blog-web-v2-front-end/api-reference/openapi.json
```

Expected: frontend OpenAPI file now has the same JSON content as the runtime capture.

- [ ] **Step 3: Format frontend OpenAPI deterministically**

```bash
jq '.' /mnt/d/end/workspace/vue/blog-web-v2-front-end/api-reference/openapi.json \
  > /tmp/blog-web-v2-openapi.json \
  && mv /tmp/blog-web-v2-openapi.json /mnt/d/end/workspace/vue/blog-web-v2-front-end/api-reference/openapi.json
```

Expected: JSON remains valid and consistently formatted.

- [ ] **Step 4: Run the contract check again**

```bash
set -o pipefail
jq -r '.paths | keys[]' /mnt/d/end/workspace/vue/blog-web-v2-front-end/api-reference/openapi.json \
  | rg '^/api/admin/|pending/count' \
  2>&1 | tee logs/api-contract-align-frontend-stale-green.log
test "${PIPESTATUS[1]}" -eq 1
```

Expected: `rg` exits `1` with no matched stale paths. The empty log file is acceptable evidence.

## Task 5: Verify Runtime And Frontend OpenAPI Match

**Files:**
- Create evidence: `logs/api-contract-align-frontend-endpoints.txt`
- Create evidence: `logs/api-contract-align-frontend-only.txt`
- Create evidence: `logs/api-contract-align-runtime-only.txt`

- [ ] **Step 1: Extract frontend endpoint list**

```bash
jq -r '.paths | to_entries[] as $p | $p.value | to_entries[] | "\(.key | ascii_upcase) \($p.key)"' \
  /mnt/d/end/workspace/vue/blog-web-v2-front-end/api-reference/openapi.json \
  | sort \
  | tee logs/api-contract-align-frontend-endpoints.txt
```

Expected: output includes `GET /api/v1/admin/articles/pending`.

- [ ] **Step 2: Compare runtime-only endpoints**

```bash
comm -23 logs/api-contract-align-runtime-endpoints.txt logs/api-contract-align-frontend-endpoints.txt \
  | tee logs/api-contract-align-runtime-only.txt
```

Expected: no output.

- [ ] **Step 3: Compare frontend-only endpoints**

```bash
comm -13 logs/api-contract-align-runtime-endpoints.txt logs/api-contract-align-frontend-endpoints.txt \
  | tee logs/api-contract-align-frontend-only.txt
```

Expected: no output.

- [ ] **Step 4: Verify removed stale endpoints**

```bash
test ! -s logs/api-contract-align-runtime-only.txt
test ! -s logs/api-contract-align-frontend-only.txt
! rg -n '"/api/admin/|pending/count' /mnt/d/end/workspace/vue/blog-web-v2-front-end/api-reference/openapi.json
```

Expected: all commands exit `0`.

## Task 6: Update Backend Audit Report

**Files:**
- Modify: `docs/api-contract/gap-report.md`

- [ ] **Step 1: Update the report status**

Replace the previous unresolved required-fix wording with a short resolved section:

```markdown
## Alignment Follow-Up

- `pending/count` remains intentionally removed from the contract; the frontend derives pending count from `GET /api/v1/admin/articles/pending?page=1&size=1`.
- Frontend OpenAPI was regenerated from Flyway-enabled backend runtime `/v3/api-docs`.
- Verification evidence:
  - `logs/api-contract-align-health.json`
  - `logs/api-contract-align-openapi-runtime.json`
  - `logs/api-contract-align-runtime-endpoints.txt`
  - `logs/api-contract-align-frontend-endpoints.txt`
```

Expected: the report explains the alignment outcome without claiming ignored `logs/` files are committed.

- [ ] **Step 2: Check report for stale unresolved wording**

```bash
rg -n "spring.flyway.enabled=false|/api/admin/|pending/count|runtime-capture-failed" docs/api-contract/gap-report.md
```

Expected: output may mention `pending/count` only as intentionally removed; no row should say the current alignment still requires bypassing Flyway.

## Task 7: Commit Changes In Each Repository

**Files:**
- Backend commit:
  - `docs/api-contract/gap-report.md`
  - `docs/superpowers/plans/2026-05-09-api-contract-alignment-plan.md`
- Frontend commit:
  - `/mnt/d/end/workspace/vue/blog-web-v2-front-end/api-reference/openapi.json`

- [ ] **Step 1: Verify backend status**

```bash
git status --short
```

Expected: backend shows only planned docs changes plus existing unrelated `?? response.log`; `logs/` remains ignored.

- [ ] **Step 2: Commit backend docs**

```bash
git add docs/api-contract/gap-report.md docs/superpowers/plans/2026-05-09-api-contract-alignment-plan.md
git commit -m "docs(api): 規劃並記錄 API 規格對齊"
```

Expected: commit succeeds on `feature/api-contract-audit`.

- [ ] **Step 3: Verify frontend status**

```bash
git -C /mnt/d/end/workspace/vue/blog-web-v2-front-end status --short
```

Expected: frontend shows only `M api-reference/openapi.json`.

- [ ] **Step 4: Commit frontend OpenAPI**

```bash
git -C /mnt/d/end/workspace/vue/blog-web-v2-front-end add api-reference/openapi.json
git -C /mnt/d/end/workspace/vue/blog-web-v2-front-end commit -m "docs(api): 更新 OpenAPI 規格對齊後端"
```

Expected: commit succeeds on `feat/mock-data-phase1`.

## Task 8: Final Verification Summary

**Files:**
- Read evidence logs and git state only.

- [ ] **Step 1: Print final backend commit**

```bash
git rev-parse --short HEAD
git log -1 --pretty=%s
```

Expected: output shows the backend docs commit.

- [ ] **Step 2: Print final frontend commit**

```bash
git -C /mnt/d/end/workspace/vue/blog-web-v2-front-end rev-parse --short HEAD
git -C /mnt/d/end/workspace/vue/blog-web-v2-front-end log -1 --pretty=%s
```

Expected: output shows the frontend OpenAPI commit.

- [ ] **Step 3: Report evidence to Yuan**

Report:
- Flyway-enabled backend startup result.
- Health capture result.
- Runtime/frontend endpoint diff result.
- Backend commit SHA.
- Frontend commit SHA.
- Any remaining untracked files, especially `response.log`.
