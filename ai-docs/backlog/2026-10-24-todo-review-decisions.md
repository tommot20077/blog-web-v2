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

- **拆服務對 JOIN 的影響**：facade 批次查詢在單體內只多一次 DB 往返（PERF-02 的 `getAuthorInfoByIds` 為範本）；拆服務後改為批次 API ＋ 快取，熱點才考慮副本。**以他模組欄位做 filter / sort 的查詢無法以批次補值取代**——公開作者設計的 `GET /api/v1/authors`（`articles JOIN users`，以 nickname 篩、以文章數排）屬此類，須在 UUID 設計討論中一併決定寫法
- 新增程式碼（含公開作者功能）的 facade 簽章應直接採用 UUID，避免日後再改一次

## 6.1 SEC-01 / Q7

- **SEC-01 定案（a）**：正式拓撲確認為 Client → Cloudflare → nginx → k3s Ingress → app。本 repo 先做第 3 層（`ClientIpResolver`，patch 在 `ai-docs/backlog/patches/2026-09-04-client-ip-resolver.patch`；移除 `forward-headers-strategy: framework`；刪除 `ArticleController` 說反的 JavaDoc），預設 `app.http.trusted-proxy-count=0`，prod 依第 2 層結果設 `1`。第 1、2 層（只放行 Cloudflare、nginx realip 覆寫 XFF、nginx 設定收進版控）由 Yuan 於 infrastructure repo 處理
- **Q7 結案**：Yuan 確認無長期環境跑過舊版，無需維運清理

## 6.2 T2 冪等反模式（RACE-01/03/04/08/15）

- 定案（a）：**併入各模組的 UUID 改造 PR**，重寫 repository 時改用 `INSERT ... ON CONFLICT`，依 affected rows 決定計數；每項附真 Postgres 的雙擊 IT；完成後把 TEST-10 的 red E2E（雙擊按讚應 200）收進 CI gate
- 例外：RACE-03（註冊撞 UNIQUE → 500）與 Q8 改寫同一段註冊流程，**隨 Q8 一起修**，避免同一段碼動兩次

## 6.3 DATA-01（標籤計數）

- 定案（a）：前台顯示的 `articleCount` = **已發布**文章數；刪除守衛改為「`article_tags` 是否有任何列」（含草稿），不再依賴計數器
- 機制：收到事件時對受影響標籤**重算**（tag 模組自 `article_tags` 取 article uuid → `ArticleFacade.filterPublishedUuids` → 分組計數），以 `ZADD` 設定 Redis 熱門分數，不再 `incrementScore`；另加每日全量校正排程（比照 `TrendingRefreshJob`）
- 補 `article_tags.tag_id` 索引（PERF-07，新 migration，同步 `schema.md`）
- 界限寫進 JavaDoc：單一標籤超過約 1 萬篇時改為增量計數＋定期校正
- TDD 起點：先改寫 TEST-02（把盲目 +1 固化成規格的測試）
- **現在獨立修**（與 UUID 改造幾乎無重疊）

## 6.4 其他

- ArchUnit 守衛（Transaction+MQ、`@PreAuthorize`）**實際已於 `29cbc09`（2026-09-06）落地**，backlog 檔未更新
- 登記簿對帳：Yuan 同意進行（docs-only PR）

---

## 8. 最終待辦與施工順序（2026-10-24）

| # | 項目 | repo | 依賴 / 備註 |
|---|---|---|---|
| 1 | findings 登記簿對帳 | 後端（docs） | 無；進行中 |
| 2 | 前端相依升級 ＋ DOMPurify 收緊（§3） | 前端 | 無 |
| 3 | SEC-01 第 3 層 `ClientIpResolver` | 後端 | 無；第 1、2 層 Yuan 於 infrastructure |
| 4 | Q9 refresh cache-aside（修 SEC-26） | 後端 | 無 |
| 5 | DATA-01 標籤計數重算 ＋ 索引 ＋ 每日校正 | 後端 | 無 |
| 6 | Q8 註冊 email 一律回成功 ＋ RACE-03 | 後端 | **#3 上線後** |
| 7 | UUID 全面改造的設計討論（ARCH-30 擴大） | 兩 repo | 無；**須在 #8 實作前完成**，以決定新端點寫法 |
| 8 | 公開作者功能 後端 PR → 重抓快照 → 前端 PR（含 SEC-04 第 2 段） | 兩 repo | #7 |
| 9 | SEC-04 第 3 段：`MAX_SIZE` 1000 → 100 | 後端 | #8 前端上線後 |
| 10 | UUID 改造：各模組一個 PR（含該模組 T2 修復） | 後端（＋事件） | #7 |
| 11 | SEC-16 互動路徑「可讀才可互動」 | 後端 | #10 的 comment、reading 完成後 |
| 12 | ES 重建：存 `authorUuid`、移除 `username`，拿掉搜尋的批次作者查詢 | 後端 | #10 |
| 13 | CSP Report-Only | 前端 nginx／infrastructure | 無 |
| 14 | 第 3 段：拆 CASCADE、事件驅動刪除 | 後端 | ARCH-16 可觀測性 |
| 15 | 查證 ArticleList 左欄 Tags 連結以名稱代 slug | 前端 | 無 |
| — | 對帳後仍 OPEN 的其他 findings（FE-01 全域錯誤處理、FE-02 未存檔守衛等）重新排序 | 兩 repo | #1 |

**#7 已完成**（2026-10-24）：設計 `docs/plans/2026-10-24-cross-module-uuid-design.md`、實作計畫 `docs/plans/2026-10-24-cross-module-uuid-implementation-plan.md`。範圍擴大為 (c)：含拆跨模組 FK 與事件驅動刪除；JWT 直接切換（全體重新登入，無正式環境）；Q9 併入 0b；Q11 以「例外清單逐步清空」解決；`user_preferences.user_id` 追加為第 18 個欄位；`files` 表 DROP。#10～#12 併入該計畫的 M／Z 階段。

---

## 7. 盤點時發現、需另行處理

- **`findings.md` 狀態嚴重過時**：本次確認 Q4、Q5、Q6（含 AUTH-01、DATA-03）、Q7 皆已做完卻仍標待決策 → 需排一次「登記簿對帳」
- ES 文件 `ArticleDocument.author` 存有 `username`（登入識別符），目前未輸出但不應存（Q10 落地後重建索引時一併移除）
- 前端 ArticleList 左欄 Tags 以標籤**名稱**組 `/tags/${tag}` 連結，TagView 預期 slug——名稱含大寫或空白時可能連到空頁，待查證
- **認證快取回填競態（BUG-2026-002 型，既有）**：撤銷 afterCommit 刪 `user:auth:{uuid}` 後，併發請求若在 commit 前讀到舊 DB 狀態，會以舊值回填快取（TTL 7 天），舊 access token 仍可通過。T0b-2 後 `/auth/refresh` 也會回填，多一個進入點（但需先通過 refresh ZSet 檢查，窗口很窄）。根治方向：回填帶版本比對的原子寫入（Lua）或回填短 TTL。（T0b-2 審查 F1）
- **`comment_likes` 缺以 `comment_id` 開頭的索引（既有）**：UNIQUE 為 (user_uuid, comment_id)，刪除留言時 FK CASCADE 找讚列需全表掃描。V25 前即存在（舊 UNIQUE 同樣以 user_id 開頭），不在 M1 授權內。修法：新 migration 加 `idx_comment_likes_comment(comment_id)`。（M1-b 回報）
- **寫入與文章刪除的窗口（M1-b 起，設計接受）**：寫入端在 `existsByUuid` 與 INSERT 之間文章被刪，會留下孤兒列，直到每日 `OrphanReconciliationJob` 子類別清除；FK 拿掉後的固有代價。適用：留言（M1-b）、按讚與收藏（M2-b）、劃線（M2-c）。（M1-b、M2-b 回報）
- **comment 列表作者欄位缺端到端測試**：comment 模組 IT 一律 mock `UserFacade`，`UserFacadeImpl` 端由 `UserFacadeIntegrationTest` 覆蓋，兩端以 `Map<UUID, UserDisplayInfo>` 契約銜接，但沒有測試走過「真實 UserFacade → listComments → JSON」。併入 TEST-10 E2E 進 CI gate 時補 author 斷言。（M1-b 審查 MEDIUM）
- **刪除連動 queue 缺真 broker 的 binding 測試**：`comment.article-deleted`、`reading.article-deleted` 只有 config 單元測試與 ContextSmokeTest（context 能啟動），沒有測試以真 RabbitMQ 發布 `ArticleDeletedEvent` 後證明 consumer 收到。Z3（打開 MQ 重試）前補一個 blog-start 層的 Testcontainers RabbitMQ IT 涵蓋全部刪除連動 queue。（M2-b 審查）
- **閱讀進度一致性（既有，M2-c 審查以 PoC 重現；建議合併為一個 Task）**：M2-c 已修 RACE-13（flush 改 Lua compare-and-remove，at-least-once），以下仍未處理，PoC 測試存於 `ai-docs/backlog/poc/2026-10-25-reading-progress-Review2PocIT.java.txt`：
  - (a) 兩個 flusher 交錯（多實例、無 ShedLock）：F1 讀舊值停頓、F2 寫新值並清標記、F1 再寫舊值 → DB 回退（poc2）
  - (b) 完成路徑（≥ 門檻直寫 DB 並 DEL hash）與 flush 交錯 → DB 被 flush 讀到的舊值覆寫（poc3）
  - (a)(b) 修法：upsert 加單調守衛 `WHERE user_reading_progress.updated_at <= EXCLUDED.updated_at`，`updated_at` 改傳 hash 的 `updatedAt`（動到時間欄位語意），或 flush 加 Redis 鎖
  - (c) `update` 的 HMSET／EXPIRE／SADD 為三個獨立指令，EXPIRE 失敗或 JVM 中止時 hash 為新值卻無 dirty 標記 → 改單一 Lua
  - (d) `get()` 快取 miss 回填無條件 HMSET，可能覆蓋併發 update 的新值 → 改 HSETNX 語意的 Lua
  - (e) hash 缺 `progress` 欄位會成 poison pill（目前無寫入路徑會產生）；(f) Redis Cluster 下 key 無 hash tag 會 CROSSSLOT（目前單機）
- **`ReadingProgressService.update` 在交易內先 DEL 快取（既有，違反 security.md 原則 10）**：DB upsert 尚未 commit 時併發 `get()` 會 cache miss、讀到舊 DB 值並回填 Redis（TTL 數天）。修法：DEL 改 `afterCommit`，或移除不必要的 `@Transactional`。（M2-c 審查）
- **`ReadingProgressFlushJob` 的 `SMEMBERS` 無界（PERF-31 剩餘部分）**：M2-c 已把逐筆 `findIdByUuid` 改為整輪一次 `filterExistingUuids`；dirty set 一次全讀仍未處理，改 `SSCAN` 分批。
- **refresh token 可被當 access token 送進 filter（既有）**：`JwtService.validateToken` 不檢查 token type；目前因 refresh token 無 role claim 被 filter 的 catch 偶然擋下。應在 filter 明確檢查 type=access 並補測試。（T0b-2 審查）
