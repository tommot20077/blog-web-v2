# Implementation Plan: API Contract Re-Alignment 2026-05-15

## Design Reference

- Design doc: `docs/plans/2026-05-15-api-contract-realignment-design.md`
- Approved by: Yuan (2026-05-15 brainstorming session)

## Notes on scope vs project conventions

This audit produces Node/TS tooling and a markdown report, **not** Java code. The TDD rule still
applies to every script (`*.test.ts` / `*.test.js`). The Facade / JavaDoc / Spring conventions
from the writing-plans skill do not apply to Node scripts. Verification commands are
`npm test` / `node --test` instead of `./mvnw test`.

Repo legend:
- **B** = backend repo `D:/end/workspace/java/blog-web-v2`
- **F** = frontend repo `D:/end/workspace/vue/blog-web-v2-front-end`

Intermediate artefacts live under `B/logs/api-contract-2026-05-15/` (git-ignored).

---

## Tasks

### Task 1 — B / Phase 0: 後端 OpenAPI 抓取

**目標**: 啟動 backend dev profile，抓 `/v3/api-docs`，存到 `logs/api-contract-2026-05-15/backend-openapi.raw.json`，驗證可 parse、operation 數量 ≥ 70。

**步驟**:
1. 建立 `logs/api-contract-2026-05-15/` 目錄（git-ignore 已在 `.gitignore` 中以 `logs/` pattern 覆蓋，無需新增規則）。
2. 背景啟動：`./mvnw.cmd -pl blog-start spring-boot:run -Dspring-boot.run.profiles=dev`。
3. 等 `/actuator/health` 為 UP（最多 90 秒，逾時失敗）。
4. `curl -fsS http://localhost:9010/v3/api-docs > logs/api-contract-2026-05-15/backend-openapi.raw.json`。
5. 用 `jq '.paths | length'` 驗證 ≥ 50（粗略 sanity）。
6. 停止 backend（kill 子行程）。

**TDD**: 不適用（一次性資料抓取）。但要寫一個 sanity bash assertion：`jq -e '.openapi // empty' < raw.json`。

**相依**: 無

**驗證**: 檔案存在且為合法 JSON、operationCount ≥ 50。

---

### Task 2 — B / Phase 2-A: 後端 envelope normaliser（TDD）

**目標**: 寫 `docs/api-contract/scripts/normalise-backend.js`，把 backend OpenAPI 的 `ApiResponse<T>` 拆封成 `T`，輸出 `backend-openapi.normalised.json` 與 `unwrapped-responses.json`。

**TDD（先紅後綠）**:
- 在 `docs/api-contract/scripts/normalise-backend.test.js` 撰寫測試（用 `node --test`）：
  1. **Red 1**: `ApiResponse<ArticleDto>` schema → 期望 response schema 變為 `ArticleDto`。
  2. **Red 2**: `ApiResponse<Void>` → 期望 schema 為 `{ "type": "null" }`。
  3. **Red 3**: 沒有 `ApiResponse` 包裝（如直接回 binary stream）→ 期望保留原 schema 並寫進 `unwrapped-responses.json`。
  4. **Red 4**: 多層 `$ref` 解析（`ApiResponse<PageDto<ArticleDto>>`）→ 期望剝掉外層 envelope，保留 `PageDto<ArticleDto>` 結構。
  5. **Red 5**: 未被引用的 `ApiResponse*` schema → 期望從 `components.schemas` 移除。
- 每個 Red 都先 fail，再寫最小實作通過。

**相依**: Task 1（要有 raw JSON 才能在 Task 後段用真實資料 smoke test）

**驗證**: `node --test docs/api-contract/scripts/normalise-backend.test.js` 全綠；對 Task 1 真實 raw JSON 跑一次，產出 normalised + unwrapped-responses 兩個 artefact。

---

### Task 3 — F / Phase 1 scaffold: 前端 generator 工具鏈

**目標**: 在前端 repo `scripts/generate-frontend-openapi.ts` 建立骨架；新增 `package.json` devDependencies：`ts-morph`, `ts-json-schema-generator`, `openapi3-ts`, `@types/node`；新增 npm script `audit:openapi`。

**步驟**:
1. `npm install --save-dev ts-morph ts-json-schema-generator openapi3-ts`
2. 寫一個極簡 CLI：讀環境變數 `AUDIT_SOURCE_DIR`、`AUDIT_OUTPUT`，目前只輸出一個空的 OpenAPI 3.0.3 skeleton。
3. 加 npm script `"audit:openapi": "tsx scripts/generate-frontend-openapi.ts"`。

**TDD**: 不適用（純 scaffold）。寫一個冒煙測試確認 CLI 可執行並輸出有效 OpenAPI skeleton。

**相依**: 無

**驗證**: `npm run audit:openapi -- --source src/api/real --out /tmp/test.json` 輸出 OpenAPI 3.0.3 文件，`jq '.openapi'` 為 `"3.0.3"`。

---

### Task 4 — F / Phase 1: HTTP method + path extraction（TDD）

**目標**: generator 能從 service function AST 抓出 HTTP method 與 URL path（純字串 + template literal 兩種），轉成 OpenAPI path。

**TDD**:
- 在 `scripts/generate-frontend-openapi.test.ts`（vitest，前端已配置）撰寫：
  1. **Red 1**: `function foo() { return apiClient.get('/api/v1/users') }` → operation `(method: 'get', path: '/api/v1/users')`。
  2. **Red 2**: `function foo(uuid: string) { return apiClient.get(\`/api/v1/articles/${uuid}\`) }` → `(method: 'get', path: '/api/v1/articles/{uuid}')`，path param `uuid: string`。
  3. **Red 3**: `function foo() { return apiClient.get(buildUrl()) }` → warning `unresolved-path`，operation 不納入。
  4. **Red 4**: 多個 apiClient 呼叫 → warning `multi-call-wrapper`。
  5. **Red 5**: 沒有任何 apiClient 呼叫 → 完全跳過該 function（不報 warning）。
- 實作走 ts-morph：`Project`, `SourceFile`, `FunctionDeclaration|ArrowFunction`, `CallExpression.getExpression()`。

**相依**: Task 3

**驗證**: `npm test -- scripts/generate-frontend-openapi.test.ts` 全綠。

---

### Task 5 — F / Phase 1: query params + request body 抽取（TDD）

**目標**: generator 能識別 `{ params: {...} }` 為 query string、其它物件 / 變數為 request body（限 post/put/patch）。

**TDD**:
1. **Red 1**: `apiClient.get('/x', { params: { page: 1, size: 20 } })` → query params `page: number, size: number`。
2. **Red 2**: `apiClient.post('/x', payload)` 且 `payload: CreateXRequest` → request body schema = JSON Schema of `CreateXRequest`。
3. **Red 3**: `apiClient.post('/x', { name, age })` inline object → request body schema 從 inline 屬性型別推。
4. **Red 4**: `apiClient.delete('/x', { params: { force: true } })` → DELETE 不應該有 request body，但有 query params。
5. **Red 5**: 第二個參數既不是 `{ params }` 也不是合法 body → warning `ambiguous-request`。

**相依**: Task 4

**驗證**: 同 Task 4。

---

### Task 6 — F / Phase 1: response type → JSON Schema（TDD）

**目標**: generator 從 function 的 `Promise<X>` 回傳型別產 OpenAPI 200 response schema；透過 ts-json-schema-generator 將 X 轉 JSON Schema。

**TDD**:
1. **Red 1**: `function get(): Promise<Article>` → 200 response schema = JSON Schema of `Article`，且 `Article` 被加入 `components.schemas`。
2. **Red 2**: function 沒寫 explicit return → 用 TS inferred type，並發 `response-type-inferred` warning。
3. **Red 3**: `Promise<Article | null>` → schema 為 nullable Article。
4. **Red 4**: `Promise<Pick<Article, 'uuid' | 'title'>>` (utility type) → schema 為展開後的兩屬性物件。
5. **Red 5**: `Promise<T>` 其中 T 無法解析（缺型別宣告）→ schema fallback `{}` + warning `unresolvable-schema`。

**相依**: Task 4

**驗證**: 同 Task 4。

---

### Task 7 — F / Phase 1: anti-pattern + 多呼叫 wrapper 偵測（TDD）

**目標**: 在主 generator 旁邊掃 `e2e/**`、`src/components/**/*.{vue,ts}`、`src/composables/**`、`src/stores/**` 找 `apiClient.X(` / `axios.X(` 直接呼叫；產 `anti-pattern-inventory.json`。多呼叫 wrapper 已在 Task 4 處理，這裡只處理 anti-pattern。

**TDD**:
1. **Red 1**: 元件中 `apiClient.get('/foo')` → 一筆 anti-pattern 紀錄。
2. **Red 2**: Vue SFC `<script setup>` 中 `axios.post('/foo')` → 一筆，category 標記為 `direct-axios`。
3. **Red 3**: `src/stores/auth.ts` 中 `apiClient.post('/auth/refresh')` → 一筆，category `store-direct`。
4. **Red 4**: e2e/global-setup.ts 的 `axios.post` → 一筆，category `e2e-direct`。
5. **Red 5**: 完全乾淨的目錄 → 空陣列輸出。

**相依**: Task 3

**驗證**: `npm test` 全綠。

---

### Task 8 — F / Phase 1 full-repo run + path-param 命名對齊

**目標**: 對 `src/api/real/`、`src/api/*Service.ts`、`src/api/mock/` 全量掃描，產出 `frontend-openapi.json`、`frontend-mock-openapi.json`、`frontend-generator-warnings.json`。把 Task 2 的 `backend-openapi.normalised.json` 餵進 generator，對每個 `(method, path)` 把 frontend OpenAPI 的 path param 名稱重命名為 backend 的版本。

**步驟**:
1. CLI 接 `--align-with <backend-openapi-path>` 參數。
2. 對 frontend 每個 operation 找 backend 同 `(method, path)` 的 parameters：若 path param 命名不同（例如 frontend 用 `articleUuid`，backend 用 `uuid`），把 frontend operation 的 path placeholder 與 parameter name 換成 backend 命名（**不**動 TS code）。
3. 對「backend 完全找不到對應 `(method, path)`」的 frontend operation：保留原樣（會在 oasdiff 階段被識別為 frontend-only）。

**TDD**:
- 一個整合測試：給 sample backend + sample frontend service 檔，跑全流程，斷言輸出的 frontend OpenAPI 中 path param 已對齊。

**相依**: Task 2, 4, 5, 6, 7

**驗證**: 真實前端 repo 全量跑一次，產出 3 個 JSON。Warnings ratio < 30% 才能往下走（手動 spot-check 確認）。

---

### Task 9 — B / Phase 2 diff orchestration

**目標**: 寫 `docs/api-contract/scripts/run-audit.{sh,ps1}` 跑 Phase 0–3。整合 `oasdiff` (Docker `tufin/oasdiff`)；備援 fallback 用 npm `openapi-comparator`。

**步驟**:
1. 主腳本驅動：
   - 檢查必要工具（`curl`, `jq`, `node`, `docker`）。
   - Phase 0：呼叫 backend startup helper（可重用 Task 1 的指令）。
   - Phase 1：在前端 repo 跑 `npm run audit:openapi -- --align-with $BACKEND_NORMALISED`。
   - Phase 2-A：normalise backend → `backend-openapi.normalised.json`（直接用 Task 2 的 script）。
   - Phase 2-B：`docker run --rm -v ...:/work tufin/oasdiff diff /work/backend-norm.json /work/frontend.json -f json > oasdiff-real.json`。Docker 不可用 → fallback npm。
   - Phase 2-C：再跑一次 `frontend.json` vs `frontend-mock.json` → `oasdiff-mock.json`。
   - Phase 3：呼叫 Task 10 的 build-report。
2. Fail-fast 守則：
   - health check 失敗 → exit 1。
   - generator warning ratio > 30% → exit 1。
   - oasdiff 非 diff-existed 的非 0 exit → exit 1。

**TDD**: 不容易做 end-to-end 單測，改為「以 fixture JSON 跑全流程，驗證最終 markdown 中含預期 section」的 smoke test。寫進 `docs/api-contract/scripts/run-audit.test.js`。

**相依**: Task 2, 8

**驗證**: 用 fixture (含 2 個 path、其中 1 個 schema drift) 跑全流程，最終 markdown 含 `Schema Drift` section 並列出該端點。

---

### Task 10 — B / Phase 3 report builder（TDD）

**目標**: 寫 `docs/api-contract/scripts/build-report.js`，吃 `oasdiff-real.json` + `oasdiff-mock.json` + `anti-pattern-inventory.json` + `frontend-generator-warnings.json`，產出 markdown。

**TDD**:
1. **Red 1**: oasdiff 報「path added on right side」（前端有 backend 沒）→ markdown `Required Fixes` 桶有該行。
2. **Red 2**: oasdiff 報「path deleted on right side」（backend 有 frontend 沒）+ 該 path 不在任一 anti-pattern inventory → `Backend-only (deferred)` 桶。
3. **Red 3**: `paths.modified` 的 schema drift → `Schema Drift` 桶，含 backend type / frontend type。
4. **Red 4**: anti-pattern inventory 非空 → `Anti-patterns` 桶列出檔案 + 行號。
5. **Red 5**: 之前 deferred 的 6 個（bookmark/highlight/readingProgress/versionPreference/articleVersion/series）在 frontend OpenAPI 中存在且 matched → `Resolved Since 2026-05-09` 桶。
6. **Red 6**: 同一份輸入跑兩次 → 完全 byte-identical 輸出（deterministic）。

**相依**: 無（用 fixture JSON 寫測試即可）

**驗證**: `node --test docs/api-contract/scripts/build-report.test.js` 全綠。

---

### Task 11 — Cross-repo / Phase 3 end-to-end run + sanity 抽檢

**目標**: 完整跑一次 `run-audit.ps1`（Windows 本機），產出 `2026-05-15-gap-report.md`。隨機抽 5 個端點手動驗證分桶正確（誤差 ≤ 1）。

**步驟**:
1. `./docs/api-contract/scripts/run-audit.ps1` 完整跑。
2. 開啟產出的 gap report，逐一檢視：
   - 6 個曾 deferred 是否進 `Resolved Since 2026-05-09`。
   - `frontend-only` 是否確實為前端 typo / 殘留。
   - schema drift 是否確實是後端最近有改的端點。
3. 從 backend OpenAPI 隨機抽 5 個 path，到前端 service 翻找對應呼叫，跟報告分桶比對。

**TDD**: 不適用（end-to-end manual verification）

**相依**: Task 1–10

**驗證**: 抽檢誤差 ≤ 1；warning ratio < 30%。

---

### Task 12 — Cross-repo / Phase 3 commit + 收尾

**目標**: 把 gap-report、scripts、frontend generator 全部 commit；舊 `gap-report.md` 末尾加 cross-link。

**步驟**:
1. **Backend repo**:
   - Commit `docs/api-contract/2026-05-15-gap-report.md`
   - Commit `docs/api-contract/scripts/{normalise-backend.js,run-audit.sh,run-audit.ps1,build-report.js}` + 對應 test files
   - 編輯 `docs/api-contract/gap-report.md` 末尾加：`> Newer audit: see docs/api-contract/2026-05-15-gap-report.md`
   - 確認 `logs/api-contract-2026-05-15/` 不在 git 索引中（受 `logs/` gitignore 覆蓋）
2. **Frontend repo**:
   - Commit `scripts/generate-frontend-openapi.ts` + tests
   - Commit `package.json` / `package-lock.json` 變更
3. Commit message 依 git-convention（`docs(api-contract):` / `chore(scripts):`）；繁體中文 subject。

**TDD**: 不適用。

**相依**: Task 11

**驗證**: 兩個 repo `git status` 乾淨；`git log -1` 含預期訊息。

---

## Risk Register（執行時要主動觀察）

| 觸發條件 | 動作 |
|---|---|
| Task 1 backend 啟動失敗 | 停下，回報 Flyway / DB 狀態給 Yuan，等指示再續 |
| Task 2/4-7/10 任一 TDD Red 沒先寫就直接寫實作 | 違反 CLAUDE.md，回滾並重做 |
| Task 8 warning ratio > 30% | 停下，把 warnings 列給 Yuan 看，討論是補前端 type、調 generator 規則、或暫時降標 |
| Task 9 Docker 拉不到 oasdiff image | 切 npm fallback，記錄在 report 的 evidence section |
| Task 11 抽檢誤差 > 1 | 回 Task 10 修報告分桶規則，重跑 |

## Estimated Total: 3–5 hours
