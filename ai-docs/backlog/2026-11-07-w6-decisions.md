# W6 待決策清單：Yuan 的逐題決定（2026-11-07）

- **來源**：2026-11-06 W6 對帳後整理出 29 題需要決策的項目，每題附建議，Yuan 逐題拍板。
- **相關**：`ai-docs/findings.md`（2026-11-06 對帳附註）；W6 PR tommot20077/blog-web-v2#101、tommot20077/blog-web-v2-front-end#63。
- **狀態**：已全數定案，實作排入 W7。

## A. 安全

| # | 條目 | 決定 |
|---|---|---|
| A1 | verification_tokens 明文 | **改為只存 SHA-256**。token 改由寄信 consumer 在寄信當下產生，DB 存雜湊、信件寄明文；事件仍只帶 userUuid。重寄與新 token 取代舊 token 的語意維持不變。 |
| A2 | XSS-03 CSP | **用 Playwright E2E 收集 console 的 CSP 違規，零違規後切換為強制的 `Content-Security-Policy`**。不建收報端點。<br>**補充（同日）**：強制後 `img-src` 只允許 `'self' blob:`，作者在 markdown 中貼外部圖床網址會在讀者端破圖。**維持嚴格，不放寬**；改由編輯器偵測外部圖片，在預覽時提示「發布後無法顯示，請改用上傳」。 |
| A3 | SEC-22 | 忘記密碼、重寄驗證信、驗證碼校驗加 **per-IP 限流，沿用註冊門檻（每 IP 每 60 分鐘 10 次）**，三個端點各自計數。 |
| A4 | SEC-23 | **做通用的應用層限流元件**。超限回 429 並帶 Retry-After；已登入者以使用者計數，匿名者以 IP 計數。門檻依建議值設定，寫進 yaml，可用環境變數覆寫：<br>• 留言新增／編輯：每人每分鐘 10 次<br>• 按讚、收藏、劃線、閱讀進度：每人每分鐘 60 次<br>• 上傳：每人每小時 30 次<br>• 文章建立／更新：每人每分鐘 30 次<br>• 搜尋：每 IP 每分鐘 30 次 |
| A5 | SEC-25(b) | 文章 `content_md` **上限 200,000 字元**，超過回 400。 |
| A6 | FILE-04 | 圖片**總像素 ≤ 100MP，不限單邊**（全景圖可以通過）。上傳時拒絕，縮圖端再擋一次。 |
| A6b | 單檔上限 | **文章圖片單檔上限調為 20MB；頭像單檔維持 5MB**。配額：**AUTHOR 調為 2GB**，USER 維持 10MB。multipart、swallow、nginx 與 ingress 的上限要同步調整。HEIC 不支援。 |
| A7 | FILE-06 EXIF | **不做**（WONTFIX）。 |
| A8 | AUTH-09 | **WONTFIX**。劃線 UUID 是隨機 v4，別人的劃線也無法從任何 API 看到，沒有實際可利用的路徑。 |
| A9 | AUTH-10 | **統一權限命名**：新增 ADMIN_* 類 permission，取代 SYSTEM_CONFIG 的泛用；version 的兩個讀取端點改為 hasAuthority。 |

## B. 資料正確性

| # | 條目 | 決定 |
|---|---|---|
| B1 | 圖片綁定事件遺失 | **新增每日校正 job**：掃描最近 N 天更新過的 PUBLISHED 文章，重新解析內文並回填綁定。與 D6 一起做。 |
| B2 | RACE-05 | **回應帶 version、request 帶 version，不一致回 409**。前端提示「已在其他地方修改」並提供重新載入，不做合併。 |
| B3 | RACE-06 | **接受全域 409（OPTIMISTIC_LOCK_CONFLICT）**，標為 DONE。 |
| B4 | DATA-05 | 搜尋結果的計數**與詳情頁同源**：瀏覽數 = DB 值 + Redis 暫存增量（以 MGET 批次取得），按讚數取 DB；**移除 ES 的 sort=hot**。 |
| B5 | DATA-06 | 留言數**不包含墓碑佔位**。文章卡片與留言區統一為「未刪除的留言數」。 |
| B6 | DATA-10 | 系列篇數**全部改用 DB 即時計數**（經 article facade），不再讀 `series.article_count`，也不使用 Redis。 |
| B7 | DATA-09 | **接受**瀏覽數 flush 崩潰時遺失該批的風險，記錄後結案。 |

## C. 架構、測試、文件

| # | 條目 | 決定 |
|---|---|---|
| C1 | TEST-08／ARCH-10 | `CrossModule*IT` **改名為 `*ModuleIT`**，跨模組接縫的覆蓋交給 E2E。 |
| C2 | ARCH-14 | **以 springdoc 產生的 openapi.json 為唯一真相，刪除 `docs/api-contract/`**；**上線前補齊所有端點的 @Operation 與錯誤回應說明**。 |
| C3 | ARCH-15 | 錯誤碼**全部收進 blog-common**：搬移 comment、reading、series、version 的錯誤碼，**錯誤碼字串不變**；search 改用自己的錯誤碼，不再借用 UserErrorCode。 |
| C4 | ARCH-17 | 依 ModulePomDependencyTest 與各模組 pom 的現況，在 architecture.md **補上模組依賴矩陣與 13 個模組的清單**。只描述現況，不新增規則。 |
| C5 | ARCH-18 | **移除** version 模組 3 個 queue 的 10 分鐘 TTL，與全站一致。 |
| C6 | ARCH-26 | **拆出 `SeriesArticleItem`**，series 不再共用 ArticleSummaryResponse。 |
| C7 | ARCH-16 | **應用層先做**：Prometheus 匯出端點（不對外公開）、publisher 發送失敗計數、DLQ 深度 gauge。收集、Grafana 與告警規則隨 OCI 一起建。 |

## D. 效能與 UX

| # | 條目 | 決定 |
|---|---|---|
| D1 | PERF-01 | **現在做**：ES 全量重建改為背景執行並回報進度，分批讀取、批次取標籤，建立新索引後切換 alias。 |
| D2 | PERF-09 | **維持現狀**。 |
| D3 | PERF-22 | **回覆改為分頁（載入更多）**：每則留言預設只帶前 3 則回覆，另外提供回覆分頁端點。API 與前端都要改。 |
| D4 | PERF-35 | **內文取消 fuzziness，標題與摘要保留**。 |
| D5 | FE-02 | **離開前跳確認**：站內切頁時用確認對話框，關閉分頁或重新整理時用 beforeunload。 |
| D6 | 未綁定檔 | **每日清理建立超過 7 天、仍未綁定、且不是頭像的檔案**，與 B1 一起做（先補綁定，再清理）。 |

## 追加決策（2026-11-07，W7-b 審查後）

| # | 條目 | 決定 |
|---|---|---|
| A4-1 | 搜尋建議 | `GET /api/v1/search/suggest` 新增**獨立規則 `search-suggest`**：每 IP 每分鐘 120 次，不與 search 共用。 |
| A4-2 | 其餘寫入端點 | **全部套用限流**：<br>• 標籤追蹤併入 `interaction-write`。<br>• 文章狀態轉換、version（手動建立、還原、升版）、series 寫入併入 `article-write`。<br>• 個人資料、通知、偏好設定新增 `account-write`，每人每分鐘 30 次。<br>守衛改為掃描所有寫入端點，未套用者必須列入豁免清單並寫明理由。 |
| A4-3 | 閱讀進度 | **拆成獨立規則 `reading-progress`**：每人每分鐘 120 次，不與按讚、收藏共用額度。 |
| A4-4 | 搜尋門檻 | `search` 由每 IP 每分鐘 30 次**調高為 120 次**，以容納邊打字邊搜尋與 CGNAT 共用 IP。 |
