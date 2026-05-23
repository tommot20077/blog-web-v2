# P0 Red E2E 交接使用說明

日期：2026-05-23

這份文件給後續接手的人使用。現在的工作範圍是 Phase 1：只建立紅燈測試、契約紅燈檢查與 Phase 2 手測清單。除非 Yuan 明確宣布進入下一階段，否則不要把這些紅燈測試改成綠燈。

## Repo 與分支

後端：

- 路徑：`D:\end\workspace\java\blog-web-v2`
- 分支：`feature/p0-red-e2e`
- 目標 base：`develop`

前端：

- 路徑：`D:\end\workspace\vue\blog-web-v2-front-end`
- 分支：`feature/p0-red-e2e`
- 目標 base：`develop`

注意事項：

- 不要在 `develop` 繼續這批工作。
- Yuan 這次要求用一般分支，不使用 worktree。
- Phase 1 不改產品後端/前端實作來讓紅燈變綠。
- 真實 dev DB 的刪除或清理動作都要先問 Yuan。

## 目前新增內容

後端紅燈 E2E：

- Maven profile：`red-e2e`
- 測試位置：`blog-start/src/test/java/dowob/xyz/blog/e2e/red/*RedE2E.java`
- 既有 `-Pe2e` profile 已排除 `red/*RedE2E.java`，避免一般 E2E 誤跑紅燈測試。

前端 full-stack red Playwright：

- Playwright project：`fullstack-red`
- 測試目錄：`e2e/fullstack-red`
- Fixture：`e2e/fullstack-red/fixtures/fullstack-red.ts`
- Specs：Auth lifecycle、Author review、Reader interaction。

契約紅燈：

- Harness：`docs/api-contract/scripts/contract-red.test.js`
- Runner：`docs/api-contract/scripts/run-red-audit.ps1`
- CI 策略：`docs/api-contract/red-ci-notes.md`

Phase 2 真資料庫手測文件：

- `ai-docs/integration-tests/2026-05-23-phase2-manual-real-db-index.md`
- `ai-docs/integration-tests/2026-05-23-phase2-auth-checklist.md`
- `ai-docs/integration-tests/2026-05-23-phase2-author-review-checklist.md`
- `ai-docs/integration-tests/2026-05-23-phase2-reader-interaction-checklist.md`
- `ai-docs/integration-tests/2026-05-23-phase2-system-evidence-checklist.md`

## 前置條件

後端應在 dev profile 運行：

- Backend：`http://localhost:9010`
- Readiness：`GET http://localhost:9010/actuator/health/readiness`

前端 Playwright full-stack red 使用：

- Frontend：`http://localhost:5500`
- Backend API：`VITE_API_BASE_URL=http://localhost:9010`
- `E2E_FULLSTACK_RED=1` 時 `VITE_USE_MOCK=false`

契約紅燈 audit 預設：

- 前端 repo 在 `D:\end\workspace\vue\blog-web-v2-front-end`
- 前端 repo 已安裝 dependencies，包含 Vitest。
- 如果前端路徑不同，用 `FRONTEND_REPO` 覆蓋。

## 跑後端紅燈 E2E

在後端 repo 執行：

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
.\mvnw.cmd -pl blog-start -am test -Pred-e2e 2>&1 |
  Tee-Object -FilePath logs\p0-backend-red-suite.log
exit $LASTEXITCODE
```

目前預期結果：

- 紅燈是預期狀態。
- 最近驗證結果：`Tests run: 6, Failures: 3, Errors: 0`。
- Auth 失敗點：invalid token 的 refresh/logout 目前回 `400 A0104`，紅燈規格期待 `401`。
- Author 失敗點：rejected article resubmit 目前回 `400 A0204`，紅燈規格期待可回到 `PENDING_REVIEW`。
- Reader backend red tests 目前會通過，不要為了「全紅」刻意加假失敗。

## 跑前端 Full-Stack Red Playwright

在前端 repo 執行：

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
$env:E2E_FULLSTACK_RED = '1'
$env:VITE_API_BASE_URL = 'http://localhost:9010'
npx playwright test e2e/fullstack-red 2>&1 |
  Tee-Object -FilePath logs\p0-fullstack-red-suite.log
exit $LASTEXITCODE
```

目前預期結果：

- 紅燈是預期狀態。
- 最近驗證結果：`4 tests`，`1 passed`，`3 failed`。
- 通過項目：註冊後未驗證帳號不能 API/UI 登入。
- 失敗點都是清楚的前置資料問題：
  - `author@test.local` 不能登入。
  - `reader@test.local` 不能登入。
  - `/articles` 沒有 published article。
- 這代表 dev seed/state 尚未準備好跑完整真流程。

## 跑契約紅燈 Audit

在後端 repo 執行：

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
.\docs\api-contract\scripts\run-red-audit.ps1 2>&1 |
  Tee-Object -FilePath logs\contract-p0-endpoints-red.log
exit $LASTEXITCODE
```

目前預期結果：

- 紅燈是預期狀態。
- 最近驗證結果：`5 tests`，`3 passed`，`2 failed`。
- 目前失敗點：
  - successful response envelope mismatch。
  - frontend file upload contract 被產成 `application/json`，不是 `multipart/form-data`。
- Runner 會複製 OpenAPI artifacts 到 `logs\api-contract-red\`。
- Runner 會把詳細 Vitest failure 寫到 `logs\contract-red-audit.log`。
- Runner 不應留下後端 repo 的 `node_modules\`。
- Runner 不應留下 `docs\api-contract\red-*-gap-report.md`。

## Phase 2 真資料庫手測

只有 Yuan 明確說要進入 Phase 2 manual verification 時才使用：

- `ai-docs/integration-tests/2026-05-23-phase2-auth-checklist.md`
- `ai-docs/integration-tests/2026-05-23-phase2-author-review-checklist.md`
- `ai-docs/integration-tests/2026-05-23-phase2-reader-interaction-checklist.md`
- `ai-docs/integration-tests/2026-05-23-phase2-system-evidence-checklist.md`

手測規則：

- 不要在未取得 Yuan 同意前刪除或清理 dev data。
- 手測證據放在 `logs/manual-phase2-YYYY-MM-DD/`。
- SQL 使用目前 schema 表名：
  - `user_article_likes`
  - `user_bookmarks`
  - `files.reference_type`
  - `files.reference_id`

## 優先查看的 Logs

後端：

- `logs/p0-backend-red-suite.log`
- `logs/contract-p0-endpoints-red.log`
- `logs/contract-red-audit.log`
- `logs/phase2-manual-checklist-diff-check.log`

前端：

- `logs/p0-fullstack-red-suite.log`
- `logs/fullstack-auth-lifecycle-red.log`
- `logs/fullstack-author-review-red.log`
- `logs/fullstack-reader-interaction-red.log`
- `logs/playwright-config-fullstack-project-green.log`

## Commit 對照

後端：

- `2911009` `test(e2e): 隔離後端紅燈 e2e profile`
- `114a3bc` `test(e2e): 新增 auth 生命週期紅燈測試`
- `9506e9f` `test(e2e): 新增 author 審核流程紅燈測試`
- `69bf91e` `test(e2e): 新增 reader 互動紅燈測試`
- `86f3ab3` `test(e2e): 排除紅燈測試於既有 e2e profile`
- `c7a6471` `test(contract): 新增契約紅燈檢查 harness`
- `a9467e7` `test(contract): 新增契約紅燈 audit runner`
- `b991fd9` `test(contract): 新增 p0 旅程契約紅燈檢查`
- `77f88c2` `docs(contract): 記錄契約紅燈 ci 策略`
- `7195b2f` `docs(e2e): 新增 phase2 真資料庫手測索引`
- `0f05945` `docs(e2e): 新增 auth 真資料庫手測清單`
- `e158f98` `docs(e2e): 新增 author admin 真資料庫手測清單`
- `04efcc8` `docs(e2e): 新增 reader 真資料庫手測清單`
- `23b1aab` `docs(e2e): 新增 phase2 系統證據清單`

前端：

- `ebbdf89` `test(e2e): 隔離全端紅燈 playwright 專案`
- `ebbacc8` `test(e2e): 新增全端紅燈 playwright fixture`
- `63c1a59` `test(e2e): 新增全端 auth 紅燈流程`
- `111ba50` `test(e2e): 新增全端 author 審核紅燈流程`
- `d2448e8` `test(e2e): 新增全端 reader 互動紅燈流程`
- `dd24644` `test(e2e): 獨立全端紅燈 playwright project`

## 接手後第一步

先確認兩個 repo 都在正確分支、工作樹乾淨：

```powershell
Set-Location D:\end\workspace\java\blog-web-v2
git status -sb
git diff --check

Set-Location D:\end\workspace\vue\blog-web-v2-front-end
git status -sb
git diff --check
```

如果下一個目標是把紅燈轉綠，先重新寫一份 green phase plan。需要先決定：

- dev/test seed data 要怎麼建立。
- invalid token refresh/logout 要維持 `400` 還是改成 `401`。
- rejected article 是否允許 resubmit。
- frontend upload OpenAPI 產生器要怎麼表示 `multipart/form-data`。
- contract red 與 full-stack red 什麼時候升級為 CI blocking。
