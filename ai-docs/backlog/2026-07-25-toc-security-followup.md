# Backlog: 文章 TOC 的安全複審遺留項

- **建立日期**: 2026-07-25
- **來源**: [文章 TOC 設計](../../docs/superpowers/specs/2026-07-20-article-toc-design.md) 的獨立安全複審（攻擊者視角）
- **類型**: 安全/健壯性債（皆為範圍外，非阻擋合併）

## 背景

TOC 功能（heading id + sanitizer 白名單）經獨立複審，結論為「無阻擋合併的
finding」。其中一個 MEDIUM（去重序號超出 id 長度上限 → 死錨點）已於 T1a
（commit fe2e692）修復。以下三項經評估**接受現狀但需記錄**，避免遺失。

## 項目

### 1. TOC 錨點防護為 best-effort（命名空間內 id 碰撞）

作者可在自己文章內用 raw HTML `<h2 id="heading-<真標題slug>">` 精準撞掉真
標題的 id（該 id 符合 `^heading-[\p{L}\p{N}-]{1,64}$` 故 sanitizer 放行），
使 `document.getElementById` 回傳文件順序中第一個，讓讀者點 TOC 導到偽造區塊。

**威脅模型**：非跨使用者攻擊、非權限提升、無腳本執行。攻擊者是文章作者本人、
受害是自己文章的讀者，而作者本就能完全控制內容誤導讀者——增量風險僅「劫持
自己文章的錨點導覽」。design §2.5 已將此 trade-off 列為刻意接受。T1a 已把
JavaDoc 措辭改精確（「不符格式的 id 被剝除；符合格式者含刻意仿冒會放行」）。

**若未來要完全封閉**：於後處理階段偵測 HTML 內重複 id，僅保留渲染器
AttributeProvider 標記過的那個（需維護「渲染器產生的 id 集合」與最終 HTML
比對）。成本高、收益低（self-hijack），故不在本次做。

### 2. `content` 欄位無長度上限（既有缺口）

`CreateArticleRequest` / `UpdateArticleRequest` 的 content 僅 `@NotBlank`、
無 `@Size` 上限。這是既有缺口（非 TOC 引入），但它讓上述已修的 #1 類邊界
（極長標題）更易觸發，也是一般性的資源濫用面。建議加合理上限（依產品定義）。

### 3. `TocEntry.text` 對非 Vue 消費端非 HTML-safe

`TocEntry.text` 來自 flexmark `TextCollectingVisitor`，是**純文字**（HTML
標籤語法已被丟棄），但**未經 HTML escape**。目前唯一消費端是前端 `ArticleToc.vue`
用 `{{ }}` 插值（Vue 自動 escape），安全。

**風險在未來**：若出現非 Vue 消費端（RSS feed、email digest、`<meta>`
description）直接把 `text` 嵌入 HTML，需自行 escape，不可預設它是 HTML-safe
字串。與 [[content-html-rerender-path]] 記錄的「多消費端」考量同源——TOC 進
API（B2）的架構紅利要在多消費端出現時才需正視此點。

## 驗收標準（若日後處理）

- [ ] #1：若決定封閉，存在「重複 id 偵測 + 僅保留渲染器標記者」的後處理，並有
  「作者仿冒真標題 slug 時 TOC 仍導向真標題」的測試。
- [ ] #2：content 有明確長度上限與對應驗證測試。
- [ ] #3：新增任何非 Vue 消費端消費 `TocEntry.text` 時，該路徑對 text 做 HTML
  escape，並有測試涵蓋含 HTML 特殊字元的標題。
