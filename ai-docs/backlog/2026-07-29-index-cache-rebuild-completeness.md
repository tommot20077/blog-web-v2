# Backlog: 索引/快取重建路徑不完整（reindexAll 不清舊索引 + tag:autocomplete 無回填）

- **建立日期**: 2026-07-29
- **來源**: admin spec §5.3（reindexAll 既有已知項）＋ 2026-07-29 /qa 全站實測（tag:autocomplete 新發現）
- **類型**: 功能缺口（衍生儲存的重建路徑不完整）

## 問題

兩個同源問題：衍生儲存（ES 索引、Redis 自動完成集）只有「增量寫入」路徑，沒有「完整重建」路徑。

### 1. `reindexAll()` 用 saveAll 疊加，不清除舊索引

admin 搜尋索引頁的「重建索引」實際行為是把現有文章 saveAll 進 ES，**不先清空**：

- 已刪除文章的 document 永遠殘留在索引中，會出現在搜尋結果
- `documentCount` 虛高（實測：DB 14 篇 PUBLISHED，ES count 18）

### 2. `tag:autocomplete` ZSet 無回填機制

`/api/v1/tags/suggest` 讀 Redis ZSet `tag:autocomplete`（`TagServiceImpl.suggest`），但**唯一寫入點**是
`TagNormalizationServiceImpl:74`（建立新標籤時）。後果：

- 在該功能上線前就存在的標籤（dev 環境 12 個）永遠不在 ZSet 裡，suggest 幾乎永遠回空
- Redis flush/重建後整個 ZSet 消失，無任何補建手段
- e2e `tag-suggest.spec.ts` ×2 在 dev 環境恆掛（2026-07-29 /qa 已定性為此缺口，非迴歸）

## 建議修法（一次設計，兩個問題一起解）

「重建索引」語意升級為「重建所有衍生儲存」：

1. ES：改為 create-new-index → bulk index → alias 切換（或至少 deleteAll + saveAll in batch），消除殘留
2. Redis autocomplete：同一個重建流程掃 `tags` 表全量回填 `tag:autocomplete`
3. （可選）應用啟動時若 ZSet 為空自動回填一次，讓 dev 環境自癒

## 驗收線索

- 刪除文章後 reindex → 搜尋不到該文、documentCount 與 DB 一致
- reindex 後 `/tags/suggest?q=vu` 包含 `vue`；`tag-suggest.spec.ts` ×2 轉綠

---

## 現況（2026-09-04 更新，PR「文章下架」的 code review 回頭處理）

### 問題 1：**部分解決**

`SearchServiceImpl.reindexAll()` 在 `saveAll` 之後新增 `removeGhostDocuments()`：掃描索引現況，
刪除「ES 有、但不在本次 DB PUBLISHED 集合內」的 document。

- **已解**：已刪除／**已下架**文章的殘留 document 會被下一次「重建索引」清掉，
  `documentCount` 不再無限虛高。這也讓下架的 ES 移除**首次具備補救路徑**——在此之前，
  `article.archived` 的 MQ 或 consumer 一旦失手（NACK requeue=false 直入 DLQ），
  文章會**永久**留在搜尋結果裡，且管理員點「重建索引」也修不好。
- **仍開放**：
  1. 沒有做 create-new-index → bulk → **alias 切換**。目前是「先寫新的、再刪對不上的」，
     不是原子替換；mapping 變更仍只能靠 `SearchIndexInitializer` 啟動時的 delete + createWithMapping。
  2. 幽靈掃描用 from+size 分頁，受 `index.max_result_window`（預設 10000）限制，
     上限寫死 1000 × 10 頁；超過會 log warn 並只清掃描範圍內的殘留（本專案規模遠低於此）。
  3. 清除是 best-effort：掃描或刪除失敗只 log.error，不讓已成功的重建對外報錯。
  4. 這只是**對帳式**補救，需要有人去點「重建索引」；沒有排程對帳，也沒有自動重送 DLQ 訊息。

守衛測試：`SearchServiceTest.ReindexAllTests` 的
`reindexAll_removesGhostDocumentsMissingFromDatabase` /
`reindexAll_whenIndexMatchesDatabase_deletesNothing` / `reindexAll_whenGhostScanFails_doesNotThrow`。

### 問題 2：**未處理**

`tag:autocomplete` ZSet 仍無回填機制，唯一寫入點依舊是 `TagNormalizationServiceImpl`。
「重建索引」語意升級為「重建所有衍生儲存」的設計仍在本 backlog 內，未動。
