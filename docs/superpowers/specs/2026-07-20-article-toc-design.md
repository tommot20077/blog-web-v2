# 文章章節導覽（TOC）設計

> 日期：2026-07-20
> 範圍：`blog-module-article`（後端）+ `blog-web-v2-front-end`（前端）
> 狀態：設計定案，待實作

---

## 1. 問題

`ArticleDetail.vue:221-224` 有三顆寫死的導覽點：

```html
<div class="art-nav">
  <div class="art-nav-dot active" />
  <div class="art-nav-dot" />
  <div class="art-nav-dot" />
</div>
```

它們是純視覺裝飾，完全不能用：

| 需要 | 現況 |
|---|---|
| 可跳轉的章節清單 | 寫死正好 3 顆，與文章實際章節數無關 |
| 跳轉目標（錨點） | 文章內文**沒有任何錨點** |
| scroll-spy 更新 active | `active` 寫死在第一顆，全檔無 scroll 監聽 |
| click 綁定 | 完全沒有 |

「沒有錨點」的根因在後端 sanitizer（`ArticleMarkdownRenderer.java:51-74`）：allowlist 只放行 `class`(code/pre)、`href`(a)、`src/alt/title`(img)、`colspan/rowspan`(th/td)。**`id` 未出現在任何一條 `allowAttributes`，h1–h6 更是零屬性**。所以 `content_html` 裡的標題是光禿禿的 `<h2>標題</h2>`。

## 2. 決策與理由

### 2.1 形態：文字 TOC 側欄（非圓點）

圓點導覽是為「每屏一節」的 fullpage 捲動設計的。文章章節長度不等——第 3 節可能佔 60% 篇幅——等距圓點會誤導讀者。且「動態產生 + scroll-spy」的工程量已與文字 TOC 相當，而文字 TOC 多給了「知道每節是什麼」這個實際價值。

### 2.2 資料來源：後端產出（非前端掃 DOM）

曾評估「前端 mount 後掃 DOM 自行注入 id」的方案。它的唯一優勢是繞開「既有文章的 `content_html` 沒有 id」的問題——因為 `content_html` 是**寫入時預渲染的持久化欄位**（`Article.java:76-77`，schema V5），讀取時完全不經過渲染器，所以改渲染器不會回溯影響既有資料。

**該優勢在本專案不成立**：目前 16 篇文章全為測試資料（`content_md` 皆無 heading），站台尚未上線，無需保護的既有內容。重新 seed 即可，不需要有風險的 backfill migration。

風險消失後，後端方案的架構優勢決定結果：

1. heading id 是 markdown → semantic HTML 轉換的**標準輸出**。flexmark 解析時本就持有 AST，知道每個 Heading 的層級與文字；前端方案等於丟棄該資訊、渲染成 HTML、再於瀏覽器重新解析 HTML 把它撈回來。
2. 文件結構是**文件的屬性**，不是視圖的屬性。放後端則任何消費端可直接使用；放前端則每個消費端都要重寫掃描邏輯。
3. 前端掃描使 TOC 相依於「已渲染的 DOM」而非「內容」。

### 2.3 暴露層級：TOC 結構進 API（B2）

後端不只產 id，並將 TOC 結構作為 `ArticleResponse` 的欄位輸出。前端不再從 DOM 反推文件結構。

### 2.4 儲存：TEXT 欄位存 JSON 字串

**不使用 JSONB。** 本 repo 已踩過此坑並留下紀錄：`users.social_links` V4 建為 JSONB，V10 改為 TEXT，理由明載「Spring Data JDBC 相容」（`ai-docs/schema.md:32,563`）。全專案亦零 `@MappedCollection`，不使用 Spring Data JDBC 子集合。

TOC 恆為整體消費（不查詢其中欄位），故 TEXT + JSON 足夠，且與 `content_html` 同生命週期、同失效時機（皆於 create/update 重算）。

**但 API 契約不沿用 `socialLinks` 的缺陷**：`UserProfileResponse` 直接把 JSON 字串吐給前端。本設計於 mapper 反序列化為型別化陣列，API 回傳結構化資料。

### 2.5 id 格式：Unicode 文字 slug，`heading-` 前綴

```
id = "heading-" + slug(標題文字)
pattern = ^heading-[\p{L}\p{N}-]{1,64}$
```

- **允許 Unicode**：本站為繁體中文部落格，純 ASCII slug 會使中文標題退化為空字串。
- **`heading-` 前綴 + pattern 約束**：這是安全機制，非美觀考量。放行 `id` 屬性會開出 DOM clobbering 面——使用者可在 markdown 內嵌 raw HTML 注入任意 id（如 `<h2 id="app">`）干擾 `getElementById` 或全域名稱。pattern 使**渲染器產的 id 通過、使用者寫的 id 被剝除**，所有 id 關在 `heading-` 命名空間內。
- **不用序號**（`heading-1`）：序號不穩定。作者於中間插入章節會使後續序號全部位移，先前分享出去的 `#heading-3` 將指向不同章節。文字 slug 只要標題不改就穩定。
- **重複標題**：同一篇文章內標題文字重複時，後出現者附加序號去重。例：兩個都叫「安裝步驟」的標題 → `heading-安裝步驟`、`heading-安裝步驟-2`。

### 2.6 收錄層級：h2 + h3

h2 為主章節、h3 縮排為子節。只收 h2 會使長文 TOC 過粗；收到 h4 則側欄過雜。h1 排除（markdown 內文的 h1 通常與文章標題重複）。

## 3. 契約

```json
"toc": [
  { "id": "heading-安裝步驟", "text": "安裝步驟", "level": 2 },
  { "id": "heading-常見問題", "text": "常見問題", "level": 2 },
  { "id": "heading-port-被佔用", "text": "Port 被佔用", "level": 3 }
]
```

- 陣列順序 = 文件順序。
- 無 heading 的文章回傳空陣列 `[]`（非 null）。
- `level` 僅可能為 `2` 或 `3`。

## 4. 後端變更（`blog-module-article`）

### 4.1 `ArticleMarkdownRenderer`

- 簽章改為 `RenderResult render(String markdown)`，其中
  `record RenderResult(String html, List<TocEntry> toc)`。
- TOC 由 **flexmark AST** 取得（走訪 `Heading` 節點取 level 與文字），**不從 HTML 反解**。
- 渲染時對 h2/h3 注入 `id="heading-<slug>"`。
- sanitizer allowlist 新增：
  ```java
  .allowAttributes("id")
      .matching(Pattern.compile("^heading-[\\p{L}\\p{N}-]{1,64}$"))
      .onElements("h2","h3")
  ```
  **僅放行 h2/h3**——與實際產生 id 的層級一致。h1/h4-h6 不產 id，故亦不需放行；範圍越窄，攻擊面越小。
- `toPlainText(String)` 維持不變。

**待實作時驗證**：flexmark 產生 heading id 的確切 API 尚未實測。預期為 `HtmlRenderer.Builder` 上掛自訂 `HtmlIdGenerator`（可控前綴與去重）。若該 API 不如預期，退路是渲染後於已 sanitize 的輸出注入 id（順序比對 AST 收集的清單）。**實作前先寫一支最小驗證測試確認 API 行為，再往下寫。**

### 4.2 呼叫點（爆炸半徑）

| 位置 | 動作 |
|---|---|
| `ArticleCommandSubService.java:69` (create) | 改接 `RenderResult`，同時 set `contentHtml` 與 `toc` |
| `ArticleCommandSubService.java:142` (update) | 同上 |
| `ArticleCommandSubServiceTest.java:114` | mock 回傳型別改為 `RenderResult` |
| `ArticleCommandSubServiceTest.java:1549` | 同上（XSS 委派測試） |
| `ArticleMarkdownRendererTest` | 新增 TOC 與 id 注入案例 |

### 4.3 Schema（V19）

```sql
ALTER TABLE articles ADD COLUMN toc TEXT;
```

nullable，無 DEFAULT。**依 CLAUDE.md 強制規定，必須同步更新 `ai-docs/schema.md`**：`articles` 表欄位區塊加 `toc`，Migration Index 末尾補 V19 說明。

> 注意 Spring Data JDBC 對未設值欄位會送顯式 NULL（見專案既有教訓），故 create/update 兩條路徑都必須顯式 set。

### 4.4 Response DTO

`ArticleResponse` 與 `EditorArticleResponse` 新增 `List<TocEntry> toc`。

`ArticleResponseMapper` 需改**兩處**，反序列化儲存的 JSON 字串為 `List<TocEntry>`：

| 方法 | 行 | 動作 |
|---|---|---|
| `toResponse` | :45 起（欄位區 :71 附近） | 加 `toc` |
| `toEditorResponse` | :83 起（欄位區 :95 附近） | 加 `toc` |
| `toSummaryResponse` | :105 起（欄位區 :122 附近） | **不加**——列表頁不需要，避免無謂 payload |

空值或解析失敗回傳空陣列，不拋例外（TOC 缺失不應使文章無法讀取）。

## 5. 前端變更

- 移除 `ArticleDetail.vue:221-224` 的三顆 `.art-nav-dot` 與相關 CSS。
- 新增 TOC 側欄元件，消費 API 的 `toc` 欄位。
- 點擊項目 → 依 `id` 平滑捲動至對應標題。
- scroll-spy：捲動時高亮當前所在章節。
- 深連結：進站帶 `#heading-xxx` 時錨至該章節。**須確認與 router `scrollBehavior` 相容**——該處先前有「無條件 `top:0` 吃掉 hash」的缺陷（已修），本次不可回歸。
- h3 於側欄縮排呈現。
- `toc` 為空陣列時隱藏整個側欄（不留空殼）。

## 6. 測試策略

TDD 強制，Red → Green → Refactor。

**後端**
- `ArticleMarkdownRendererTest`：h2/h3 產生正確 id；中文標題不退化為空；重複標題去重；h1/h4 不進 TOC；無 heading 時回傳空清單；**使用者注入的 `<h2 id="app">` 之 id 被剝除**（安全回歸）。
- `ArticleCommandSubServiceTest`：create/update 皆持久化 TOC。
- `ArticleControllerIT`：回應含結構化 `toc` 陣列。

**前端**
- TOC 元件單元測試：渲染層級縮排、空陣列時不渲染、點擊發出正確錨點。
- scroll-spy 行為測試。
- 深連結錨定測試（與現有 `ui-layout-regression` 的錨點不變量並存，不可使其轉紅）。

## 7. 範圍外

- **既有 16 篇測試文章的 TOC 補值**：以重新 seed 處理，不寫 backfill migration。
- **`content_html` 無重渲染路徑**：本次確認的既有架構債——`content_html` 是寫入時快照的衍生欄位，卻無安全重算機制，導致渲染器任何語意變更都無法回溯套用。本次因無正式資料而繞過，但該問題獨立存在且遲早會撞（修 sanitizer 漏洞、加語法高亮、加註腳皆會遇到）。**應另立 backlog 記錄，不在本次範圍內解決。**
- `ArticleSummaryResponse` 不含 TOC。
- 手機窄螢幕的 TOC 呈現方式（收合／抽屜）留待實作時依實際版面決定，若成本高則另案處理。
