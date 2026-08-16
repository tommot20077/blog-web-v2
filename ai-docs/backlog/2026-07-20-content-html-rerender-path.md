# Backlog: `content_html` 缺少安全的重渲染路徑

- **建立日期**: 2026-07-20
- **來源**: [文章 TOC 設計](../../docs/superpowers/specs/2026-07-20-article-toc-design.md) §7 範圍外
- **類型**: 架構債（衍生資料無失效機制）

## 問題

`articles.content_html`（schema V5）是 Markdown 的預渲染輸出，**於寫入時計算並持久化**：

```java
// ArticleCommandSubService.java:69  (createArticle)
article.setContentHtml(markdownRenderer.render(request.getContent()));
// ArticleCommandSubService.java:142 (updateArticle)
article.setContentHtml(markdownRenderer.render(request.getContent()));
```

讀取路徑**完全不經過渲染器**，直接輸出 DB 中的字串。

因此它在本質上是一份**衍生快取**，系統卻把它當永久資料使用，而且**沒有任何重算機制**。
`ArticleMarkdownRenderer` 的任何語意變更都無法回溯套用到既有內容——只有作者主動編輯並儲存
該篇文章，才會觸發重新渲染。

`comments.content_html`（V13）為相同模式，同樣受影響——`CommentService.java:92`（新增）與
`:148`（編輯）於寫入時渲染，讀取路徑（`:102` / `:155` / `:273`）僅回傳既存值。

## 為什麼會再撞到

任何以下工作都會遇到同一面牆：

- 修補 sanitizer 的 XSS 漏洞（既有文章仍保留漏洞版本的 HTML）
- 調整 allowlist（放寬或收緊皆然）
- 新增渲染能力：語法高亮、註腳、表格增強、heading 錨點
- 修正渲染 bug

2026-07-20 的 TOC 設計即為第一次撞上。當時因站台尚未上線、文章全為測試資料而繞過
（直接重新 seed），**該解法不可複用於有正式資料之後**。

## 為什麼「寫個 migration 全部重跑」不是安全解

天真解法是寫一支 migration，取出每篇 `content_md`、以當前渲染器重跑、覆寫 `content_html`。

危險在於這不是「只加想要的那個改動」，而是**以今日渲染器重新產生所有已發布文章的 HTML**。
渲染輸出對細節高度敏感（例：現存資料中全形逗號被轉為 `&#xff0c;` 實體），只要任何一條規則
與當初不同，重算結果就會與原本不一致——**且為靜默改寫，無人收到通知，原始 HTML 已被覆蓋**。

## 待辦項目

1. **重渲染能力**：提供受控的重新渲染入口（admin 觸發或批次工具），而非一次性 migration。
   應可指定範圍（單篇／全站）、可先跑 dry-run。
2. **差異審查**：重渲染前產出 before/after diff 報告，讓變更可被檢視而非靜默套用。
   至少要能標記「重算後內容長度顯著縮短」這類疑似掉內容的案例。
3. **渲染器版本標記**：於 `articles` 記錄產生該 `content_html` 的渲染器版本，
   使「哪些文章仍停留在舊版渲染」成為可查詢的事實，而非未知數。
4. **範圍涵蓋 comments**：`comments.content_html` 為相同模式，方案需一併涵蓋。

## 驗收標準

- [ ] 存在可指定範圍的重渲染入口，且支援 dry-run（不寫入）。
- [ ] dry-run 能輸出 before/after 差異摘要，並標記疑似內容遺失的項目。
- [ ] 可查詢出「`content_html` 由哪一版渲染器產生」，據以找出待重渲染的文章。
- [ ] 上述機制同時適用於 `articles` 與 `comments`。
