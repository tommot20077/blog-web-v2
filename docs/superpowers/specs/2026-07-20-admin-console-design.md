# Admin 後台殼層設計

> 日期：2026-07-20
> 範圍：`blog-web-v2-front-end`（主要）+ `blog-module-search`（一支新端點）
> 狀態：設計定案，待實作

---

## 1. 問題

`adminService.ts` 有 10 個方法，**只有 4 個有 UI**（全在 `/admin/review`）：

| 方法 | 端點 | 有 UI |
|---|---|---|
| `getPendingArticles` | `GET /api/v1/admin/articles/pending` | ✅ |
| `getPendingCount` | （由上者衍生） | ✅ |
| `publishArticle` | `POST /api/v1/articles/{uuid}/publish` | ✅ |
| `rejectArticle` | `POST /api/v1/articles/{uuid}/reject` | ✅ |
| `createCategory` | `POST /api/v1/admin/categories` | ❌ |
| `updateCategory` | `PUT /api/v1/admin/categories/{uuid}` | ❌ |
| `deleteCategory` | `DELETE /api/v1/admin/categories/{uuid}` | ❌ |
| `updateTag` | `PUT /api/v1/admin/tags/{id}` | ❌ |
| `deleteTag` | `DELETE /api/v1/admin/tags/{id}` | ❌ |
| `reindexSearch` | `POST /api/v1/admin/search/reindex` | ❌ |

路由層僅 `/admin/review` 一條，且其 meta **沒有 layout**——admin 目前完全沒有殼層。

## 2. 已驗證的前提

- **後端 4 個 admin controller 全部存在且有測試**：`AdminArticleController`、`AdminCategoryController`、`AdminTagController`、`AdminSearchController`，權限一律 `@PreAuthorize("hasAuthority('SYSTEM_CONFIG')")`。
- **讀取所需資料已全部在線上**：
  - `GET /api/v1/categories` 回傳完整 `CategoryResponse`（含 `description`、`sortOrder`）；前端 `categoryService.ts:12` 的 `mapCategory()` 自行收窄為 `{id,name,slug}` 丟棄了兩欄。
  - `GET /api/v1/tags/all` 回傳 `List<Tag>` 原始 entity，已含 `color`、`icon`、`description`、`parentId`、`usageCount`；前端 mapper 同樣收窄。
  - 前端的 `id` 即後端的 `uuid`（`mapCategory` 中 `id: raw.uuid`），無識別碼不一致問題。
- **刪除皆有後端防護**：
  - 分類：`CategoryServiceImpl.java:99-102`，底下有文章 → `CATEGORY_HAS_ARTICLES`
  - 標籤：`TagServiceImpl.java:207-212`，`usageCount > 0` 或有文章關聯 → `TAG_IN_USE`

## 3. 殼層架構

### 3.1 問題：`ShellRail` 不可直接沿用

`ShellRail.vue` 是**使用者工作區專用**，非通用殼層：

```ts
type ShellPage = 'bookmarks' | 'my-articles' | 'my-stats' | 'settings'
const NAV_ITEMS: ReadonlyArray<{...}> = [ /* 元件內部寫死常數 */ ]
```

導覽項目是元件內部常數、型別是封閉 union。admin 導覽項目完全不同。

### 3.2 決定：抽共用 presentational base

新增無狀態基座元件，承載 rail 的版面與互動；`ShellRail` 與新的 `AdminRail` 各自提供導覽資料來組合它。

**約束（重要）**：`ShellRail` 的**對外 props 與 emits 逐字不變**。它目前背負著 768px 站名擠壓修復（T7）、`settingsChildren` 樹狀子項、`requiresAuthor` 條件顯示；四個既有頁面與 `ui-layout-regression` 回歸 spec 都相依於它。本次只做「把呈現邏輯搬進基座」的內部重構，外部行為零變更。

基座職責：接受導覽項目、當前 active、可選的樹狀子項；負責呈現、展開/收合、768px 收窄行為。不碰路由（選取子項僅 emit）。

### 3.3 路由

| 路徑 | 頁面 | 狀態 |
|---|---|---|
| `/admin` | 總覽儀表板 | 新增 |
| `/admin/review` | 待審文章 | 既有，補上 admin 殼層 |
| `/admin/categories` | 分類管理 | 新增 |
| `/admin/tags` | 標籤管理 | 新增 |
| `/admin/search` | 搜尋索引 | 新增 |

全部 `meta: { requiresAuth: true, requiredRole: 'ADMIN' }`。

## 4. 儀表板（`/admin`）

四格指標，**全部為真實資料**：

| 格子 | 來源 |
|---|---|
| 待審文章數 | `adminService.getPendingCount()` |
| 分類數 | 分類清單長度 |
| 標籤數 | 標籤清單長度 |
| 搜尋索引 | `GET /api/v1/admin/search/status`（新端點，見 §5） |

**設計原則**：不顯示無法取得真實資料的指標。本專案已有反例——`StatsView.vue`（`/stats`「站台數據」）整頁為寫死 mock（`StatsView.vue:9` 註解自承），且掛在 ShellRail 導覽上、使用者點得到。儀表板不得重蹈此覆轍。

> `/stats` 為假資料一事本身需要處理，但**不在本次範圍**，另行討論。

## 5. 後端變更（`blog-module-search`）

### 5.1 新端點

```
GET /api/v1/admin/search/status
@PreAuthorize("hasAuthority('SYSTEM_CONFIG')")
```

回應：

```json
{ "documentCount": 13, "lastReindexAt": "2026-07-20T21:30:00", "healthy": true }
```

- `documentCount`：`articleSearchRepository.count()`
- `lastReindexAt`：Redis 時間戳，從未重建過時為 `null`
- `healthy`：查詢 ES 成功為 `true`；ES 不可達時**捕捉例外**回傳 `false` 並將 `documentCount` 設為 `null`，使儀表板能顯示「ES 離線」而非整格壞掉

### 5.2 記錄重建時間

`SearchServiceImpl.reindexAll()`（:200-208）於完成後寫入 Redis 時間戳。該類別已注入 `redisTemplate`（用於搜尋熱門榜），無新依賴。鍵名依 `RedisKeyConstant` 既有慣例新增常數。

**不使用 DB 欄位**——此為運維狀態而非業務資料，且 Redis 已在用，可免去一支 migration。

### 5.3 範圍外的既有問題（記錄，不修）

`reindexAll()` 以 `articleSearchRepository.saveAll(documents)` 疊加寫入，**未先清除既有索引**。已刪除或已下架的文章文件可能殘留於索引中，使 `documentCount` 高於實際已發布文章數。本次僅如實顯示 ES 的文件數，不修此行為；應另立 backlog。

## 6. 分類管理（`/admin/categories`）

- 表格欄位：名稱、slug、描述、排序。
- **需要不收窄的讀取路徑**：新增取得完整 `CategoryResponse` 的方法，不沿用會丟欄位的 `getCategories()`。既有 `getCategories()` 供編輯器使用，維持不變（避免影響既有呼叫端）。
- 新增／編輯：表單含 name、slug、description、sortOrder。
- 刪除：確認對話框 → 呼叫 API → **必須呈現 `CATEGORY_HAS_ARTICLES` 業務錯誤**。
  分類清單**沒有文章數欄位**，故無法預先禁用刪除按鈕，只能於失敗時明確告知原因。不可靜默失敗。

## 7. 標籤管理（`/admin/tags`）

- 表格欄位：名稱、slug、文章數、顏色、圖示、描述。
- **可編輯欄位僅 `color` / `icon` / `description`**——`UpdateTagRequest` 只有這三個，name 與 slug 後端不接受修改。UI 須反映此限制（該兩欄為唯讀），不得提供改不動的輸入框。
- 刪除：`articleCount > 0` 時預先禁用按鈕；仍須處理後端 `TAG_IN_USE` 兜底（清單數字可能過期）。

## 8. 搜尋索引（`/admin/search`）

- 顯示 §5.1 的狀態。
- 「重建索引」按鈕：確認對話框 → 執行中狀態（按鈕禁用 + 進行中指示）→ 完成後重新拉取狀態並回饋結果。
- 重建為長時間、影響全站的操作，不可一鍵直接執行。

## 9. 測試策略

TDD 強制，Red → Green → Refactor。

**後端**
- `AdminSearchControllerTest`：狀態端點回傳正確結構；非 `SYSTEM_CONFIG` 權限遭拒。
- `SearchServiceImplTest`：`reindexAll()` 完成後寫入時間戳；ES 不可達時狀態查詢回傳 `healthy=false` 而非拋出。

**前端**
- rail 基座元件單元測試：項目呈現、active 高亮、樹狀展開、768px 收窄。
- **`ShellRail` 既有測試須全數維持綠燈且不修改**——這是「對外行為未變」的證據。若需修改既有測試，即代表重構破壞了介面，應停下重新檢視。
- 各 admin 頁：清單渲染、表單驗證、刪除確認流程、業務錯誤（`CATEGORY_HAS_ARTICLES` / `TAG_IN_USE`）的呈現。
- 權限：非 ADMIN 無法進入各 admin 路由。

## 10. 範圍外

- `/stats` 為 mock 資料（另案）。
- `reindexAll()` 未清除舊索引文件（另立 backlog）。
- `GET /api/v1/tags/all` 直接回傳 entity 而非 DTO，`Tag` 新增欄位會自動外洩至公開 API（另案）。
- 標籤的 `parentId`（階層）不納入本次 UI。
- 使用者管理（`USER_MANAGE` / `USER_BAN` 權限存在，但無對應 API）。
