# 部署步驟：搜尋索引改為 alias ＋ 背景全量重建（W7-f／D1、PERF-01）

- **建立日期**: 2026-11-07
- **狀態**: 待部署時執行（隨 W7-f 合併後的第一次部署）
- **來源**: W7-f 實作與審查（M1～M3、m1～m7）；決策 `2026-11-07-w6-decisions.md` D1
- **本檔讀者**: 執行部署的人、之後觸發全量重建的管理員

> 放在 backlog 而不是 `security.md`：內容是部署操作，不是權限規則；`security.md` 依檔案所有權表為「先問」。
> 若之後有正式的 runbook 位置，請把本檔搬過去並在 CLAUDE.md 加索引（CLAUDE.md 也是「先問」）。

---

## 0. 一句話

讀寫改走 alias `blog_articles`（背後是 `blog_articles_v*` 實體索引）。**首次部署、rolling update 完全結束後，手動跑一次 `POST /api/v1/admin/search/reindex`**；rolling update 進行中不要觸發重建。

## 1. 首次部署時會自動發生的事

- 啟動時 `SearchIndexInitializer` 發現 `blog_articles` 是改版前的實體索引 → 建立 `blog_articles_v{timestamp}`、以 ES 伺服器端 `_reindex` 複製全部文件，
  再於同一個 `_aliases` 請求內刪除舊實體索引並建立 alias（原子）。部落格量級為秒級。
- 遷移任一步失敗：保留原實體索引、記 ERROR，不擋啟動；搜尋與增量索引照常在實體索引上運作，之後的全量重建會以同樣的原子切換完成遷移。
- 全新環境（名稱不存在）：建立 `blog_articles_v1` 與 alias。

## 2. 部署後必做

1. **等 rolling update 完全結束**（所有 pod 都是新版）。
2. 觸發一次全量重建：`POST /api/v1/admin/search/reindex`（需 `ADMIN_SEARCH`）→ 立即回 202 與 `jobId`。
3. 輪詢 `GET /api/v1/admin/search/status` 的 `reindex.state` 直到 `COMPLETED`（`FAILED` 時看 `reindex.error`）。

**為什麼要手動跑一次**：啟動時的遷移是 `_reindex` 複製，**不記錄重建期間的變動**（沒有 journal）；複製到切換之間，
仍在跑舊版的 pod 寫進舊實體索引的變動會遺失。全量重建從 DB 重新寫入，把這段落差補回來。

## 3. 何時不要觸發重建

- **rolling update 進行中**：舊版 pod 的 listener 不會把變動記進重建變動紀錄（journal），重建期間由舊版 pod 處理的發布／下架
  會在切換後遺失。

## 4. ES index template（若有）

- 若 ES 以 index template 套用 IK 分詞器，template 的 `index_patterns` **必須涵蓋 `blog_articles_v*`**（實體索引名稱）；
  alias 名稱 `blog_articles` 不會被 template 比對。只比對 `blog_articles` 的 template 對新建的實體索引不生效，中文分詞會退回預設。
- 本 repo 內找不到 template 設定（應在 infrastructure repo），部署前請確認。

## 5. 異常處置

- 狀態訊息含「狀態不明，需人工檢查 GET _alias/blog_articles」：alias 切換請求回報失敗且當下無法確認結果，系統未刪任何索引。
  執行 `GET _alias/blog_articles` 確認 alias **只指向一個**實體索引，再以實體名稱刪除多餘的 `blog_articles_v*`。
- 啟動日誌出現「alias 'blog_articles' 同時指向多個實體索引」：經 alias 的寫入會被拒絕；觸發一次全量重建即可收斂為單一索引。
- 作業顯示「重建作業已中斷」：執行它的 pod 中途停止（鎖已到期），直接重新觸發即可；殘留的 `blog_articles_v*`（不在 alias 上者）可手動刪除。
