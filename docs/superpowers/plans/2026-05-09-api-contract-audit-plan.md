# API Contract Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a complete front-end/back-end API contract audit using runtime OpenAPI from the dev backend as the primary source.

**Architecture:** Start the backend with `dev` profile and capture `/v3/api-docs` as the runtime contract. Cross-check it against controller annotations, front-end real service usage, E2E direct API calls, and the existing front-end `api-reference/openapi.json`, then write three evidence-backed audit documents.

**Tech Stack:** Spring Boot 3, Springdoc OpenAPI, Maven, Bash, `curl`, `jq`, `rg`, Vue 3 front-end TypeScript services.

---

## File Structure

- Create `logs/api-contract-openapi-runtime.json`: runtime OpenAPI JSON captured from the dev backend.
- Create `logs/api-contract-runtime-endpoints.txt`: normalized `METHOD path` list from runtime OpenAPI.
- Create `logs/api-contract-frontend-openapi-endpoints.txt`: normalized `METHOD path` list from the front-end checked-in OpenAPI file.
- Create `logs/api-contract-controller-mappings.raw.txt`: raw controller mapping evidence from backend Java files.
- Create `logs/api-contract-frontend-real-usage.raw.txt`: raw `apiClient.*` usage from front-end real services.
- Create `logs/api-contract-frontend-e2e-usage.raw.txt`: raw direct API usage from front-end E2E/global setup.
- Create `docs/api-contract/backend-endpoints.md`: backend endpoint matrix from runtime OpenAPI plus controller cross-check.
- Create `docs/api-contract/frontend-usage.md`: front-end service and E2E API usage inventory.
- Create `docs/api-contract/gap-report.md`: categorized differences and recommended follow-up actions.

The front-end repository path is `/mnt/d/end/workspace/vue/blog-web-v2-front-end`.

---

### Task 1: Capture Runtime OpenAPI From Dev Backend

**Files:**
- Create: `logs/api-contract-openapi-runtime.json`
- Create: `logs/api-contract-runtime-endpoints.txt`
- Create: `logs/api-contract-frontend-openapi-endpoints.txt`
- Read: `blog-start/src/main/resources/application-dev.yaml`
- Read: `blog-start/src/main/resources/application.yaml`

- [ ] **Step 1: Confirm working branch and workspace**

Run:

```bash
git branch --show-current
git status --short
```

Expected:

```text
feature/api-contract-audit
?? response.log
```

`response.log` is unrelated and must stay untracked.

- [ ] **Step 2: Prepare logs directory**

Run:

```bash
mkdir -p logs
```

Expected: command exits with status `0`.

- [ ] **Step 3: Start backend with dev profile**

Run in a long-running terminal:

```bash
mvn -pl blog-start spring-boot:run -Dspring-boot.run.profiles=dev 2>&1 | tee logs/api-contract-backend-dev.log
```

Expected:

```text
Started BlogWebV2Application
Tomcat started on port 9010
```

If the command fails because PostgreSQL, Redis, RabbitMQ, Elasticsearch, or MinIO is unavailable, stop this task and record the failing dependency in `docs/api-contract/gap-report.md` under `Runtime OpenAPI Capture Failure`.

- [ ] **Step 4: Verify backend health**

Run from another terminal:

```bash
curl -sS http://localhost:9010/actuator/health | tee logs/api-contract-health.json
```

Expected:

```json
{"status":"UP"}
```

- [ ] **Step 5: Capture runtime OpenAPI**

Run:

```bash
curl -sS http://localhost:9010/v3/api-docs | tee logs/api-contract-openapi-runtime.json
```

Expected: file starts with an OpenAPI JSON object containing `"openapi"` and `"paths"`.

- [ ] **Step 6: Extract runtime endpoint list**

Run:

```bash
jq -r '.paths | to_entries[] as $p | $p.value | keys[] | "\(. | ascii_upcase) \($p.key)"' logs/api-contract-openapi-runtime.json | sort | tee logs/api-contract-runtime-endpoints.txt
```

Expected: output contains paths such as:

```text
GET /api/v1/articles
POST /api/v1/auth/login
GET /v3/api-docs is not expected here because this command lists application API paths only
```

- [ ] **Step 7: Extract checked-in front-end OpenAPI endpoint list**

Run:

```bash
jq -r '.paths | to_entries[] as $p | $p.value | keys[] | "\(. | ascii_upcase) \($p.key)"' /mnt/d/end/workspace/vue/blog-web-v2-front-end/api-reference/openapi.json | sort | tee logs/api-contract-frontend-openapi-endpoints.txt
```

Expected: output is a sorted endpoint list from the front-end checked-in OpenAPI file.

- [ ] **Step 8: Commit captured runtime evidence is not required**

Do not commit `logs/*.json`, `logs/*.txt`, or `logs/*.log` unless Yuan explicitly asks. They are evidence files for local audit work.

---

### Task 2: Collect Backend Controller Mapping Evidence

**Files:**
- Create: `logs/api-contract-controller-mappings.raw.txt`
- Create: `docs/api-contract/backend-endpoints.md`

- [ ] **Step 1: Collect raw controller mapping lines**

Run:

```bash
rg -n "@(RestController|RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)" -g "*.java" | tee logs/api-contract-controller-mappings.raw.txt
```

Expected: output includes controller files such as:

```text
blog-module-article/src/main/java/dowob/xyz/blog/module/article/controller/ArticleController.java
blog-module-user/src/main/java/dowob/xyz/blog/module/user/controller/AuthController.java
```

- [ ] **Step 2: Create API contract docs directory**

Run:

```bash
mkdir -p docs/api-contract
```

Expected: command exits with status `0`.

- [ ] **Step 3: Write backend endpoint document**

Create `docs/api-contract/backend-endpoints.md` with this structure:

```markdown
# Backend Endpoints

## Source

- Primary: `logs/api-contract-openapi-runtime.json`
- Runtime endpoint list: `logs/api-contract-runtime-endpoints.txt`
- Controller cross-check: `logs/api-contract-controller-mappings.raw.txt`
- Backend profile: `dev`
- Runtime base URL: `http://localhost:9010`

## Runtime OpenAPI Endpoint Matrix

| Method | Path | OpenAPI operationId | Tags | Notes |
|---|---|---|---|---|

## Controller Cross-Check

| Controller | Base Path | Method Mapping | Effective Path | Runtime OpenAPI Status |
|---|---|---|---|---|

## Controller/OpenAPI Differences

| Difference | Evidence | Classification | Action |
|---|---|---|---|
```

Fill `Runtime OpenAPI Endpoint Matrix` from `logs/api-contract-runtime-endpoints.txt` and operation metadata from `logs/api-contract-openapi-runtime.json`.

Fill `Controller Cross-Check` from `logs/api-contract-controller-mappings.raw.txt`.

Use these classification values only:

```text
matched
controller-only
runtime-openapi-only
needs-investigation
```

- [ ] **Step 4: Verify backend document has no empty required sections**

Run:

```bash
rg -n "^\| Method \| Path|^\| Controller \| Base Path|^\| Difference \| Evidence" docs/api-contract/backend-endpoints.md
```

Expected: the three table headers are present.

---

### Task 3: Collect Front-End Service And E2E API Usage

**Files:**
- Create: `logs/api-contract-frontend-real-usage.raw.txt`
- Create: `logs/api-contract-frontend-e2e-usage.raw.txt`
- Create: `docs/api-contract/frontend-usage.md`
- Read: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/src/api/real/*.ts`
- Read: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/e2e/**/*.ts`

- [ ] **Step 1: Collect real service usage**

Run:

```bash
rg -n "apiClient\.(get|post|put|patch|delete)" /mnt/d/end/workspace/vue/blog-web-v2-front-end/src/api/real -g "*.ts" | tee logs/api-contract-frontend-real-usage.raw.txt
```

Expected: output includes files such as:

```text
src/api/real/authService.ts
src/api/real/articleService.ts
src/api/real/commentService.ts
```

- [ ] **Step 2: Collect E2E and setup direct API usage**

Run:

```bash
rg -n "(/api/v1/|/api/admin/|/actuator/health|request\.(get|post|put|patch|delete)|fetch\()" /mnt/d/end/workspace/vue/blog-web-v2-front-end/e2e -g "*.ts" | tee logs/api-contract-frontend-e2e-usage.raw.txt
```

Expected: output includes files such as:

```text
e2e/global-setup.ts
e2e/integration/*.spec.ts
```

- [ ] **Step 3: Write front-end usage document**

Create `docs/api-contract/frontend-usage.md` with this structure:

```markdown
# Front-End API Usage

## Source

- Real services: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/src/api/real`
- E2E direct usage: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/e2e`
- Real service evidence: `logs/api-contract-frontend-real-usage.raw.txt`
- E2E evidence: `logs/api-contract-frontend-e2e-usage.raw.txt`

## Real Service Usage

| Service File | Method | Path Pattern | Query Params | Request Body | Notes |
|---|---|---|---|---|---|

## E2E Direct Usage

| E2E File | Method | Path Pattern | Purpose | Notes |
|---|---|---|---|---|

## Front-End Usage Notes

| Topic | Evidence | Impact |
|---|---|---|
```

For wrapper files such as `src/api/articleService.ts` that re-export mock or real implementations, document only the real implementation under `Real Service Usage`.

- [ ] **Step 4: Verify front-end document has no empty required sections**

Run:

```bash
rg -n "^\| Service File \| Method|^\| E2E File \| Method|^\| Topic \| Evidence" docs/api-contract/frontend-usage.md
```

Expected: the three table headers are present.

---

### Task 4: Write Gap Report

**Files:**
- Create: `docs/api-contract/gap-report.md`
- Read: `docs/api-contract/backend-endpoints.md`
- Read: `docs/api-contract/frontend-usage.md`
- Read: `logs/api-contract-runtime-endpoints.txt`
- Read: `logs/api-contract-frontend-openapi-endpoints.txt`

- [ ] **Step 1: Compare runtime OpenAPI with checked-in front-end OpenAPI**

Run:

```bash
comm -23 logs/api-contract-runtime-endpoints.txt logs/api-contract-frontend-openapi-endpoints.txt | tee logs/api-contract-runtime-not-in-frontend-openapi.txt
comm -13 logs/api-contract-runtime-endpoints.txt logs/api-contract-frontend-openapi-endpoints.txt | tee logs/api-contract-frontend-openapi-not-in-runtime.txt
```

Expected: any output is evidence for `doc-mismatch`.

- [ ] **Step 2: Write gap report**

Create `docs/api-contract/gap-report.md` with this structure:

```markdown
# API Contract Gap Report

## Summary

| Category | Count | Highest Priority | Notes |
|---|---:|---|---|

## Required Fixes

| Priority | Category | Endpoint | Evidence | Recommended Action |
|---|---|---|---|---|

## Documentation Updates

| Priority | Category | Endpoint | Evidence | Recommended Action |
|---|---|---|---|---|

## Deferred Front-End Coverage

| Priority | Category | Endpoint | Evidence | Reason To Defer |
|---|---|---|---|---|

## Runtime OpenAPI Capture Failure

Only include rows in this section when the dev backend could not provide `/v3/api-docs`.

## Known Initial Checks

| Check | Evidence | Result |
|---|---|---|
```

After creating the table, add one concrete row for `Admin API prefix` and one concrete row for `Reading/series/version/preference coverage`. Each row must cite the relevant runtime or front-end evidence file.

Use only these category values:

```text
matched
frontend-missing
frontend-extra
path-mismatch
doc-mismatch
not-yet-planned
controller-openapi-mismatch
runtime-capture-failed
```

Use only these priority values:

```text
必修正
文件需更新
可暫緩
```

- [ ] **Step 3: Verify known initial findings were resolved into evidence**

Run:

```bash
rg -n "Admin API prefix|Reading/series/version/preference coverage|/api/admin|/api/v1/admin|reading|series|version|preference" docs/api-contract/gap-report.md
```

Expected: command finds the known checks and their recorded evidence.

---

### Task 5: Verify Audit Artifacts

**Files:**
- Verify: `docs/api-contract/backend-endpoints.md`
- Verify: `docs/api-contract/frontend-usage.md`
- Verify: `docs/api-contract/gap-report.md`

- [ ] **Step 1: Check generated docs exist**

Run:

```bash
test -s docs/api-contract/backend-endpoints.md
test -s docs/api-contract/frontend-usage.md
test -s docs/api-contract/gap-report.md
```

Expected: all commands exit with status `0`.

- [ ] **Step 2: Scan for incomplete markers**

Run:

```bash
rg -n "unfilled marker|empty required value" docs/api-contract
```

Expected: no output.

- [ ] **Step 3: Verify every gap row has evidence**

Run:

```bash
rg -n "^\| (必修正|文件需更新|可暫緩) \|" docs/api-contract/gap-report.md
```

Expected: every returned row has non-empty `Evidence` and `Recommended Action` or `Reason To Defer` columns.

- [ ] **Step 4: Check git status**

Run:

```bash
git status --short
```

Expected:

```text
?? docs/api-contract/
?? logs/
?? response.log
```

`logs/` and `response.log` should not be staged unless Yuan explicitly requests evidence files in git.

---

### Task 6: Commit Audit Documents

**Files:**
- Stage: `docs/api-contract/backend-endpoints.md`
- Stage: `docs/api-contract/frontend-usage.md`
- Stage: `docs/api-contract/gap-report.md`
- Do not stage: `logs/`
- Do not stage: `response.log`

- [ ] **Step 1: Stage only audit documents**

Run:

```bash
git add docs/api-contract/backend-endpoints.md docs/api-contract/frontend-usage.md docs/api-contract/gap-report.md
```

Expected: command exits with status `0`.

- [ ] **Step 2: Confirm staged files**

Run:

```bash
git diff --cached --name-only
```

Expected:

```text
docs/api-contract/backend-endpoints.md
docs/api-contract/frontend-usage.md
docs/api-contract/gap-report.md
```

- [ ] **Step 3: Commit**

Run:

```bash
git commit -m "docs(api): 完成前後端 API 規格稽核"
```

Expected:

```text
[feature/api-contract-audit <sha>] docs(api): 完成前後端 API 規格稽核
```

- [ ] **Step 4: Report final state**

Run:

```bash
git log --oneline -1
git status --short
```

Expected:

```text
<sha> docs(api): 完成前後端 API 規格稽核
?? logs/
?? response.log
```

Report to Yuan:

- Commit SHA
- Files created under `docs/api-contract/`
- Any `必修正` gap count
- Any runtime capture failure

---

## Self-Review

Spec coverage:

- Runtime OpenAPI with dev profile: Task 1.
- Controller static scan as cross-check: Task 2.
- Front-end real service and E2E usage: Task 3.
- Existing `api-reference/openapi.json` as checked object: Task 1 and Task 4.
- Three audit documents: Tasks 2, 3, and 4.
- Evidence and verification: Task 5.
- Commit without logs: Task 6.

Incomplete-marker scan:

- The implementation output files must not contain unresolved instruction text.
- Task 5 scans only `docs/api-contract` so the plan's instructional text does not create false positives.

Scope check:

- This plan only audits contracts and writes documents.
- It does not change front-end services, back-end controllers, API behavior, or UI.
