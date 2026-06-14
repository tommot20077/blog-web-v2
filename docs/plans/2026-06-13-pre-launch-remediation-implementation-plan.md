# Implementation Plan: 上線前 Remediation

## Design Reference
- Design doc: `docs/plans/2026-06-13-pre-launch-remediation-design.md`
- Approved by: Yuan
- 分支：`feature/pre-launch-remediation`

## 圖例
- 🧪 **Code 任務**（TDD：Red → Green → Refactor，可由 subagent 執行）
- 🛠️ **Ops/Config 任務**（git/部署/infra，需 review；部分為 Yuan 手動或閘門）
- 後端測試指令（Windows）：`.\mvnw.cmd test -pl <module> -am --no-transfer-progress`，輸出存 `./logs/`
- 前端（repo：`D:\end\workspace\vue\blog-web-v2-front-end`）：`npm run test` / `npx vue-tsc -b` / `npm run build`

---

## 第一批：🔴 硬 Blocker

### Task 0 — 🛠️【Yuan / infra】輪換 tommot40 憑證
- **目標**：`infrastructure/.env.prod` 的 `tommot40` → 新密碼；重建 k3s `infra-secret`；重啟 middleware + 後端。
- **相依**：無（最優先、可獨立）
- **驗證**：服務以新憑證啟動成功；舊密碼失效。

### Task 1 — 🛠️ repo: 移除 application-dev.yaml 追蹤
- **目標**：`git rm --cached blog-start/src/main/resources/application-dev.yaml`；確認 `.gitignore` 對新檔生效；新增 `application-dev.yaml.example`（佔位符）入 git。
- **相依**：無
- **驗證**：`git status` 顯示 dev.yaml 不再被追蹤；本機檔案仍在。

### Task 2 — 🧪 blog-module-article / Service: ArticleQueryService.getArchive()
- **目標**：新增方法回「全部已發布文章」精簡投影（uuid/title/slug/publishedAt/tags），供年度歸檔。
- **TDD**：先寫 `ArticleQueryServiceTest#getArchive_returnsAllPublishedProjection` 確認 Red，再實作。
- **相依**：無
- **驗證**：`.\mvnw.cmd test -pl blog-module-article -am --no-transfer-progress`

### Task 3 — 🧪 blog-module-article / Controller: GET /api/v1/articles/archive
- **目標**：新增端點回傳歸檔投影列表。
- **TDD**：先寫 `ArticleControllerIT#getArchive_returns200WithList` 確認 Red，再實作。
- **相依**：Task 2
- **驗證**：`.\mvnw.cmd test -pl blog-module-article -am --no-transfer-progress`

### Task 4 — 🧪 blog-module-tag / Service: TagService.getAllTags()
- **目標**：新增方法回全標籤 + 各自文章數（無 limit）。
- **TDD**：先寫 `TagServiceTest#getAllTags_returnsAllWithCount` 確認 Red，再實作。
- **相依**：無
- **驗證**：`.\mvnw.cmd test -pl blog-module-tag -am --no-transfer-progress`

### Task 5 — 🧪 blog-module-tag / Controller: GET /api/v1/tags/all
- **目標**：新增端點回全標籤列表。
- **TDD**：先寫 `TagControllerIT#getAllTags_returns200` 確認 Red，再實作。
- **相依**：Task 4
- **驗證**：`.\mvnw.cmd test -pl blog-module-tag -am --no-transfer-progress`

### Task 6 — 🧪 前端 / View: NotFoundView 接 recommendService.getTrending
- **目標**：移除 mock，改 `getTrending('7d', 4)` + loading/empty/error 狀態。
- **TDD**：先寫/更新 NotFoundView 元件測試確認 Red，再實作。
- **相依**：無（recommend 端點已存在）

### Task 7 — 🧪 前端 / Service+View: articleService.getArchive() + ArchiveView
- **目標**：新增 `articleService.getArchive()`；ArchiveView 移除 mock、改用真實資料，年度分組留前端。
- **TDD**：先寫 service/元件測試確認 Red，再實作。
- **相依**：Task 3

### Task 8 — 🧪 前端 / Service+View: tagService.getAllTags() + TagsIndexView 標籤雲
- **目標**：新增 `tagService.getAllTags()`；TagsIndexView 標籤雲改真實資料。
- **TDD**：先寫 service/元件測試確認 Red，再實作。
- **相依**：Task 5

### Task 9 — 🧪 前端 / View: TagsIndexView Series 段接 seriesService.list
- **目標**：移除寫死 Series，改 `seriesService.list({ size: 100 })`；無資料則隱藏該段。
- **TDD**：先寫元件測試確認 Red，再實作。
- **相依**：無（series 端點已存在）

### Task 10 — 🛠️ 前端 / Config: prod API URL
- **目標**：`.env.production` 的 `VITE_API_BASE_URL` → `https://90030.xyz`。
- **相依**：無
- **驗證**：`npm run build` 後 dist 內含 `https://90030.xyz`、不含 localhost。

### Task 11 — 🧪 前端 / Config+Test: apiClient fallback 埠號
- **目標**：`apiClient.ts` fallback `8080` → `9010`。
- **TDD**：更新 `apiClient.test.ts` 對齊預期 baseURL，先確認 Red 再改。
- **相依**：無

---

## 第二批：🟡 應修

### Task 12 — 🧪 blog-common / Constant: RedisKeyConstant IP 限流 key
- **目標**：新增 IP 層級登入/註冊限流的 Redis key + TTL/上限常數。
- **TDD**：先寫 `RedisKeyConstantTest`（或對應）確認 Red，再加。
- **相依**：無
- **驗證**：`.\mvnw.cmd test -pl blog-common -am --no-transfer-progress`

### Task 13 — 🧪 blog-module-user / Service: AuthService IP 層級限流
- **目標**：`/login`、`/register` 以 client IP 計數限流，與既有 user.id 鎖定並存。
- **TDD**：先寫 `AuthServiceTest#login_blockedByIpRateLimit` / `register_blockedByIpRateLimit` 確認 Red，再實作。
- **相依**：Task 12
- **驗證**：`.\mvnw.cmd test -pl blog-module-user -am --no-transfer-progress`

### Task 14 — 🧪 blog-module-user / Controller: 擷取 client IP
- **目標**：AuthController 取 `X-Forwarded-For`/RemoteAddr 傳入 service（信任反代）。
- **TDD**：先寫 `AuthControllerTest`/IT 驗證 IP 傳遞與限流回應碼，確認 Red 再實作。
- **相依**：Task 13
- **驗證**：`.\mvnw.cmd test -pl blog-module-user -am --no-transfer-progress`

### Task 15 — 🛠️【閘門】infra: 定位後端 app 部署清單
- **目標**：找出後端 app 實際部署方式（k3s deployment / docker run）所在，作為 Task 16 前置。
- **相依**：無
- **驗證**：明確指出清單檔案位置。

### Task 16 — 🛠️ infra / Config: prod env 注入
- **目標**：部署清單設 `SPRING_PROFILES_ACTIVE=prod`、`APP_CORS_ALLOWED_ORIGINS=https://90030.xyz`、`JWT_PRIVATE_KEY`、`DATABASE_URL`、`APP_FRONTEND_BASE_URL=https://90030.xyz`。
- **相依**：Task 15
- **驗證**：以 prod profile 啟動，CORS/profile 生效。

### Task 17 — 🛠️ 前端 / Config: Dockerfile + nginx
- **目標**：multi-stage Dockerfile（node build → nginx serve dist），nginx 含 SPA history fallback。
- **相依**：無
- **驗證**：build image 並本機起容器，SPA 路由可直接深連結。

---

## 第三批：🟢 Nice-to-have

### Task 18 — 🧪 blog-module-file / Constant: FileErrorCode
- **目標**：新增 `FileErrorCode`（實作 `IErrorCode`）供檔案操作例外。
- **TDD**：先寫對應測試確認 Red，再加。
- **相依**：無
- **驗證**：`.\mvnw.cmd test -pl blog-module-file -am --no-transfer-progress`

### Task 19 — 🧪 blog-module-file / Service: FileServiceImpl 例外改寫
- **目標**：4 個 `RuntimeException`（L92/122/207/262）→ `BusinessException(FileErrorCode.*)`。
- **TDD**：先寫 `FileServiceTest` 驗證丟出 BusinessException，確認 Red 再改。
- **相依**：Task 18
- **驗證**：`.\mvnw.cmd test -pl blog-module-file -am --no-transfer-progress`

### Task 20 — 🧪 blog-module-recommend / Test: 補單元測試
- **目標**：`RecommendServiceImpl` 補 Service 單元測試（trending/related/降級/空集合）。
- **TDD**：純補測試（驗證既有行為）。
- **相依**：無
- **驗證**：`.\mvnw.cmd test -pl blog-module-recommend -am --no-transfer-progress`

### Task 21 — 🧪 blog-common / Constant: 密碼規則加強
- **目標**：`PasswordPolicy.PATTERN` 加大小寫/特殊字元要求 + 訊息文案。
- **TDD**：先更新 `PasswordPolicyTest` 對齊新規則確認 Red，再改。
- **相依**：無
- **驗證**：`.\mvnw.cmd test -pl blog-common -am --no-transfer-progress`

### Task 22 — 🧪 前端 / View: 密碼複雜度即時提示
- **目標**：註冊/改密碼頁即時提示對齊新規則。
- **TDD**：先寫元件測試確認 Red，再實作。
- **相依**：Task 21

### Task 23 — 🛠️【閘門】settings 欄位後端支援盤點
- **目標**：盤點 `useSettings.ts` localStorage-only 欄位，分類「需新增後端支援」vs「已支援只差前端接線（website/socialLinks）」。
- **相依**：無
- **驗證**：產出欄位對照清單。

### Task 24 — 🧪 blog-module-user / Service+Controller: 設定持久化端點
- **目標**：為需後端支援的設定欄位（avatar URL、通知偏好等）新增持久化端點。
- **TDD**：先寫 Service/Controller 測試確認 Red，再實作。可能含 Flyway migration（須同步 `ai-docs/schema.md`）。
- **相依**：Task 23
- **驗證**：`.\mvnw.cmd test -pl blog-module-user -am --no-transfer-progress`

### Task 25 — 🧪 前端 / Service+Composable: useSettings 接後端
- **目標**：`useSettings.ts` 改真正 PATCH 後端；`User` type 補 `website`/`socialLinks`。
- **TDD**：先寫測試確認 Red，再實作。
- **相依**：Task 24

---

## 最終：🛠️【閘門】git 歷史重寫

### Task 26 — 🛠️【Yuan 確認後執行】filter-repo 移除 application-dev.yaml + force-push
- **目標**：鏡像備份 → `git filter-repo --path blog-start/src/main/resources/application-dev.yaml --invert-paths` → force-push `develop`/`main`/`feature/*`。
- **相依**：所有 code 任務完成併入 + Task 0 輪換已確認 + **Yuan 明確同意 force-push**
- **驗證**：`git log --all -- <path>` 無結果；協調所有人重 clone。

---

## 上線 Gate（全部完成後）
- 後端：全模組 `.\mvnw.cmd test` 全綠 + P0 紅燈套件維持綠。
- 前端：`vue-tsc` + `build` 綠、相關 e2e/單元測試綠。
- 契約：回應信封落差已確認非執行期 bug，不擋上線。
