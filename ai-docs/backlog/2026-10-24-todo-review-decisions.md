# 待辦盤點：Yuan 的逐項決定（2026-10-23 ~ 10-24）

- **狀態**: 討論進行中；本檔記錄已拍板事項，供後續 session 接手
- **來源**: 一次跨兩 repo 的待辦盤點對話（後端 develop @ `b232b81`、前端 develop @ `93e0748`）
- **相關設計**: `docs/plans/2026-10-23-public-author-profile-design.md`

---

## 1. ARCH-30 comment P3

- 原定：P3 維持**純重構**，新增 `ArticleFacade.existsByUuid(UUID)` 取代 `findIdByUuid` 的存在性檢查，行為不變
- **SEC-16（草稿文章可被互動）後續必須補**：comment 與 reading 四條互動路徑（留言、按讚、收藏、劃線）以同一條「可讀才可互動」規則一起修，另開 PR
- ⚠️ **已被 §6 取代**：comment 的 P3 暫停，依擴大後的計畫重排（`comments.user_id` 也要一起換）

## 2. SEC-04 第 2 段 ＋ 公開作者功能

- 作者篩選改為可搜尋的多選下拉、AuthorView 接真實資料並補入口 → 完整設計見 `docs/plans/2026-10-23-public-author-profile-design.md`（Yuan 逐段確認）
- ArticleList 改伺服器端分頁併入該設計的前端 PR

## 3. 前端相依升級與 Markdown 消毒

- 升級 axios / vite / dompurify / markdown-it / postcss / @unhead/vue，transitive 以 `npm audit fix`；動手時先重跑 `npm audit` 以當下結果為準；axios 維持精確版本釘選
- `useMarkdownRenderer` 的 DOMPurify：**移除 `iframe`**（目前無功能使用）；`style` 只保留在 Shiki 程式碼區塊（`pre.shiki` 及其子元素，以 hook 實作），其餘一律移除
- **CSP 另立一項**，先以 `Content-Security-Policy-Report-Only` 上線觀察

## 4. Q6 / Q9

- **Q6 定案**：版本還原只還原內容、不還原狀態（已由 PR #66 / #68 實作）→ `findings.md` 的 Q6、AUTH-01、DATA-03 應改為 DONE 並附證據
- **Q9 定案（a'）**：`/auth/refresh` 改走與 `JwtAuthenticationFilter` 相同的 cache-aside（Redis 命中即用、miss 查 DB 回填），抽成共用元件；修掉 SEC-26（auth hash miss 誤判停權）
  - 維運規則：直接改 DB 的 role / status 後須 `DEL user:auth:{userId}`
  - 待辦：日後做 admin 改角色功能時，必須經 `SessionRevoker`（afterCommit）並 bump tokenVersion
  - 不做 refresh token 輪替（多分頁併發 refresh 會誤判重用）

## 5. Q4 / Q5 / Q7 / Q8

- **Q4、Q5 實際已完成**（`6ab7d34` 收窄 permitAll 並校正 Public Endpoints 表）→ findings 應改 DONE
- **Q7 程式已完成**（PR #64）；剩維運刪除既有環境的 `article.published` queue——待 Yuan 確認是否有長期環境跑過舊版
- **Q8 定案（a）**：註冊時 email 重複改為一律回成功並寄通知信給原信箱主人；username / nickname 重複維持明確提示。**前置：SEC-01（`X-Forwarded-For` 可偽造）必須先修**，否則 IP 限流可繞過

## 6. Q10 ＋ ARCH-30 擴大

- **Q10 定案（b2）**：跨模組識別**全面**改為 UUID——文章、系列、**使用者**都換，連同 facade 簽章、儲存層外鍵、事件（全部帶 `eventId`）
- **Yuan 確認有拆服務的打算**，故本項服務於「保留拆服務可能」這個目標，第 3 段（拆 `ON DELETE CASCADE`、改事件驅動刪除，前置 ARCH-16 可觀測性）也在路線上
- 施工原則：**以模組為單位一次改完**——每個模組一個 PR，該模組所有表上所有指向他模組的 Long 外鍵一次走完 expand → 雙寫 → 切換 → 移除，並同步 facade 與事件；附守衛測試
- 範圍盤點（2026-10-24）：指向 `users(id)` / `series(id)` 的 Long 外鍵 13 條；11 個事件類別，帶 Long 識別者：`authorId`×4、`articleId`×2、`seriesId`×1、`userId`×2；有 `eventId` 者 5 個
- 不變：各模組**自己的主鍵**維持 Long（`users.id`、`series.id`、`articles.id`），模組內部關聯（如 `article_categories.article_id`）維持 Long
- **待專門設計討論決定**：
  - JWT subject / `@AuthenticationPrincipal` 改帶 uuid → 部署後全體重新登入（prod 未上線，需 Yuan 確認）
  - Redis key（`user:auth:{userId}`、refresh key 等）改鍵
  - 13 條新外鍵的 P1（回填、NOT NULL）與 P4（DROP）授權——9/28 授權只涵蓋 6 張表的 `article_id`
  - 拆服務後 `users` 不能再被他模組直接 JOIN——`architecture.md`「reference data 可 JOIN」條文與各 mapper 的 `JOIN users` 是否一併處理
  - 模組順序

## 7. 盤點時發現、需另行處理

- **`findings.md` 狀態嚴重過時**：本次確認 Q4、Q5、Q6（含 AUTH-01、DATA-03）、Q7 皆已做完卻仍標待決策 → 需排一次「登記簿對帳」
- ES 文件 `ArticleDocument.author` 存有 `username`（登入識別符），目前未輸出但不應存（Q10 落地後重建索引時一併移除）
- 前端 ArticleList 左欄 Tags 以標籤**名稱**組 `/tags/${tag}` 連結，TagView 預期 slug——名稱含大寫或空白時可能連到空頁，待查證
