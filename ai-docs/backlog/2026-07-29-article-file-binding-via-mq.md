# Backlog: article→file 檔案綁定改走既有 MQ 事件，解同步耦合

- **建立日期**: 2026-07-29（想法由 Yuan 於 2026-07-26 session 提出，本檔為制度化記錄）
- **來源**: 檔案存取控制實作（feature/file-access-control）的架構檢討
- **類型**: 架構債（跨模組同步耦合可降級為非同步）

## 現況

文章儲存/建立後，article 模組**同步**呼叫 file 模組回填「檔案→文章」綁定：

- `ArticleCommandSubService.createArticle` / `updateArticle` → `articleFileBinder.bindFilesToArticleSafely(uuid, content)`（best-effort，失敗不影響主流程）
- 版本還原路徑同樣有一次回填（6650470「版本還原路徑補上檔案綁定回填」）

這造成 article→file 的直接依賴（曾引發 article↔file 循環依賴事故，後以拆出 ArticleLookupFacade 打斷）。

## Yuan 的判斷（原話要旨）

- **綁定方向（寫路徑）可以改走 MQ**：`ArticleContentChangedEvent`（SAVED/PUBLISHED/RESTORED）已經存在且涵蓋所有內容變更時點——file 模組加一個 consumer 掃內文回填綁定即可，article 模組可完全移除對 file 綁定的同步呼叫。本來就是 best-effort，非同步化沒有語意損失。
- **查詢方向（讀路徑）不行**：`canRead` 授權判斷需要即時答案（HTTP 請求當下），不能走事件。

## 前置條件 / 注意

- BUG-003 修復（f8da329）後 `createArticle` 也發 SAVED 事件了，事件覆蓋面已齊
- consumer 需冪等（同一篇文重複掃描綁定結果一致——現行 bindFilesToArticleSafely 本身即冪等）
- 遷移時注意事件與同步呼叫的過渡期不要雙寫出競態；建議直接一刀切
- 完成後 article 模組對 file 模組的依賴應只剩查詢 facade，可考慮配 ArchUnit 規則固定（見 2026-07-07-archunit-guards.md）

## 驗收線索

- article 模組 imports 零 file-binding 相關類
- 上傳圖→存草稿→（MQ 消化後）file_metadata.article_uuid 正確回填
- 既有檔案權限 e2e（草稿 403/作者 302/發布 302）全綠
