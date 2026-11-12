# 上線前 Remediation 設計

**日期**：2026-06-13
**分支**：`feature/pre-launch-remediation`（自 `feature/p0-red-e2e` HEAD 開出）
**範圍決定**：全部做完（3 硬 blocker + 應修 + nice-to-have）
**目標**：正式對外上線（網域 `https://90030.xyz`，前後端同域，反代/ingress 在 infra repo 外管理）

> 本設計由現況盤點（4 個平行調查 agent + infra repo + 前端 repo 查證）沉澱而成。
> 後端 14 模組於 2026-05-30 全綠、P0 紅燈 6 測試已轉綠；本批處理的是「測試綠之外」的上線阻擋項。

---

## 已確認的關鍵事實

- 正式網域：`https://90030.xyz`（前後端同域；後端 app port 9010）。
- prod 祕密注入：k3s Secret `infra-secret` + `JWT_PRIVATE_KEY` env；`application-prod.yaml` 無預設值（fail-safe）。
- **憑證外洩**：`tommot40` 是 dev 與 prod **共用**密碼，且 dev 那組寫死在已被 git 追蹤的 `application-dev.yaml`（連同內網 IP `10.0.0.214`）。infra repo 本身乾淨（只追蹤空佔位範本）。
- 安全 backlog B-1/B-2/B-6 已修；前端 A-1/A-2 已實作。
- ⚠️ 待釐清：infra repo 只管 middleware，**後端 app 的部署清單不在 infra repo**，實作 E 時需先定位。

---

## 第一批：🔴 硬 Blocker

### A. 憑證外洩處理
外洩僅限後端 `application-dev.yaml`。三步驟：

1. **輪換（Yuan 在 infra，最優先）**：改 `infrastructure/.env.prod` 的 `tommot40` → 新密碼，重建 k3s `infra-secret`，重啟 middleware + 後端。這是真正的修復。
2. **停止追蹤**：`git rm --cached blog-start/src/main/resources/application-dev.yaml`；確認 `.gitignore` 規則對新檔生效；新增 `application-dev.yaml.example`（佔位符）入 git，真實 dev.yaml 只留本機。
3. **歷史重寫（最後做、force-push 前再確認）**：`git filter-repo` 從所有歷史/分支移除 `application-dev.yaml` → force-push `develop`/`main`/`feature/*`。先做鏡像備份；需協調所有人重 clone。限制：GitHub 舊 PR diff 可能仍快取 → 故「輪換」不可省。

### B. 前端正式環境設定
- `.env.production` 的 `VITE_API_BASE_URL` → `https://90030.xyz`（同域，組成 `https://90030.xyz/api/v1/...`）。
- `apiClient.ts` fallback 埠號 `8080` → `9010`，消除不一致。

### C. 前端三頁接真實資料（移除寫死 mock）
- **NotFoundView**：`recommendService.getTrending('7d', 4)` + loading/empty/error 狀態。後端不需改。
- **ArchiveView**：後端新增 `GET /api/v1/articles/archive`（全部已發布文章精簡欄位 uuid/title/slug/publishedAt/tags），前端新增 `articleService.getArchive()`，年度分組留前端。
- **TagsIndexView**：
  - 標籤雲：後端新增 `GET /api/v1/tags/all`（全標籤 + 文章數），前端 `tagService.getAllTags()`。
  - Series 段：改接現有 `seriesService.list({ size: 100 })` 顯示真實系列；無資料則隱藏。
- 後端兩個新端點走 TDD（Service 單元 + Controller IT）。`/articles/archive` 用精簡投影，後續可加快取。

---

## 第二批：🟡 應修

### D. 後端 IP 層級登入限流
應用層 Redis-based IP 限流（沿用既有 `RedisKeyConstant` 模式），對 `/auth/login`、`/register` 以 client IP 計數（X 次/視窗），與既有 user.id 鎖定並存。處理 `X-Forwarded-For`（信任反代）。TDD。

### E. CORS prod 網域 + 確保 prod profile（infra，可直接改）
- 設 `APP_CORS_ALLOWED_ORIGINS=https://90030.xyz`（同域不觸發，設為 fail-safe）。
- 確保後端部署帶 `SPRING_PROFILES_ACTIVE=prod` + 注入 `JWT_PRIVATE_KEY`、`DATABASE_URL`、`APP_FRONTEND_BASE_URL=https://90030.xyz`。
- **前置**：先定位後端 app 部署清單（k3s deployment / docker run）所在。

### F. 前端部署機制
前端 repo 加 multi-stage `Dockerfile`（node build → nginx serve `dist`，含 SPA history fallback）。外部反代/ingress 負責 `90030.xyz` 下 `/api`→後端:9010、`/`→前端。

---

## 第三批：🟢 Nice-to-have

- **G1**：`FileServiceImpl` 4 個 `RuntimeException`（L92/122/207/262）→ 新增 `FileErrorCode` + `BusinessException`。TDD。
- **G2**：`recommend` 模組補 Service 單元測試。
- **G3**：密碼規則加大小寫/特殊字元（`PasswordPolicy.PATTERN` + 測試 + 前端即時提示）。只影響新註冊/改密碼。
- **G4**：實作設定欄位的後端持久化（取代目前 localStorage-only）。`useSettings.ts` TODO：avatar URL、通知偏好等；`website`/`socialLinks` 後端已有、前端僅缺型別。實作時先釐清各欄位「需新增後端支援」vs「已支援只差前端接線」。

---

## 執行順序

1. 輪換憑證（Yuan / infra）— 可立即、獨立進行
2. 後端新端點（`/articles/archive`、`/tags/all`）— TDD，解除 C 的前端相依
3. 前端三頁 + prod API URL
4. 後端 IP 限流（D）
5. CORS / prod profile / 部署（E + F，含定位後端部署清單）
6. nice-to-have（G1–G4）
7. **最後**：git 歷史重寫（A-3）— 確認後 force-push

---

## 測試與驗收

- 後端新端點 / IP 限流 / FileService 例外 / 密碼規則：TDD（Red → Green → Refactor），測試輸出存 `./logs/`。
- 前端三頁：對應 e2e/單元測試覆蓋（移除 mock 後改驗真實 service 呼叫）。
- 上線 gate：全模組 `mvnw test` 全綠 + 前端 `vue-tsc` + `build` 綠 + P0 紅燈套件維持綠。
- 契約紅燈（回應信封）已確認為文件層落差、非執行期 bug（前端 `apiClient.ts:66-68` 正確拆信封），不擋上線。
