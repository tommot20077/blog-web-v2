# Backlog: 計數器型 consumer 補冪等（ViewCount / TagUsage）—— 堵住 at-least-once 重送導致的數字灌水

**狀態**:✅ DONE（branch `fix/consumer-idempotency-counters`，base = `origin/develop`）
**優先級**:MEDIUM（資料正確性；非阻斷。後果是瀏覽數/標籤熱度慢慢灌水,非金流級,但使用者可見）
**來源**:
- PR [#46](https://github.com/tommot20077/blog-web-v2/pull/46) code review（2026-07-18）衍生盤點。#46 把 restore/viewed 的 MQ 改為「DB commit 後才發」,此為 **at-least-once（至少送一次）** 語意,使「偶爾重送」成為設計上必然存在的情況 → 消費端冪等從此不是 nice-to-have。
- 消費端全面體檢:9 個 consumer 中僅 `SeriesArticleDeletedConsumer` 有做冪等。

## 問題描述

RabbitMQ 是 at-least-once:consumer 處理成功、但 `basicAck` 尚未送達 broker 就斷線/重啟（部署、當機）時,broker 會**重送**同一則訊息。若消費動作是「`+1`」這種非冪等操作,重送一次就多算一次。

盤點結果,**兩支計數器 consumer 是真正的洞**（都是遞增、都沒去重）:

| Consumer | 動作 | 重送後果 | 判定 |
|---|---|---|---|
| **ViewCountConsumer** | `incrementRedisViewCount(uuid)` | 瀏覽數多算（Redis 暫存,後續刷入 DB → 永久） | 🔴 有洞 |
| **TagUsageConsumer** | `tag.incrementUsage()` + `save`(DB) + Redis ZSet `+1.0` | 標籤使用數 **DB 永久灌水** + 熱門標籤排序失真 | 🔴 有洞 |

天生冪等,無需處理:`ArticleSearchListener`（索引 upsert/delete）、`ArticlePublishedConsumer`（刪快取）、`ThumbnailConsumer`（重算覆寫）；次要暫不處理:`ArticleVersionConsumer`（`retainAuto` 兜底）、email consumers（重寄一封）。

## 實作摘要（本 branch）

採 `SeriesArticleDeletedConsumer` 既有樣板,以 `IdempotencyService.markProcessed(eventId, consumerName)` 對 `(event_id, consumer_name)` 去重。

1. **事件加 `eventId`**（dedup key,producer 每次 publish `UUID.randomUUID()`）:
   - `ArticleViewedEvent`（article 模組）
   - `ArticleTagEvent`（**兩份定義**:`blog-infrastructure/.../event/` 與 `blog-module-tag/.../event/` 皆改,欄位名一致以利 Jackson 跨接）
2. **producer**:`ArticleEventPublisher.publishViewed` / `publishTagged` 各補 `UUID.randomUUID()`。
3. **consumer**:`ViewCountConsumer`（`CONSUMER_NAME = "article.view-count"`）、`TagUsageConsumer`（`CONSUMER_NAME = "tag.usage-count"`）各注入 `IdempotencyService`,handler 首行做去重 guard。
4. **相容性**:舊訊息 `eventId=null`（上線瞬間佇列殘留）→ guard 以 `eventId != null &&` 短路,照舊處理,不進 DLQ。
5. **trade-off**（沿用 `IdempotencyService` 既定語意）:markProcessed 用 `REQUIRES_NEW`,「寧可漏處理一次也不重複扣」——標記後若後續失敗 nack 進 DLQ,該筆計數會少算而非多算。對 view/tag 計數可接受。

## 測試（TDD Red→Green）

- `ViewCountConsumerTest`:**4 passed**（新增：同一事件重送→只加一次；`eventId=null`→照舊處理不去重）
- `TagUsageConsumerTest`:**5 passed**（同上兩案，另斷言 DB `usageCount` 與 Redis 熱門分數皆只 `+1`）
- 回歸:`blog-infrastructure`(45) / `blog-module-tag`(69) / `blog-module-article`(101) unit tests 全綠;IT 類編譯通過（IT 對事件用 `any()` matcher,不受 signature 影響）。
- 兩案皆先確認紅（重送→計數 =2 / `expected: 1`）再實作 guard 轉綠。

## 備註

- 這是「補既有慣例的必要配套」,不新增規範。若要把「消費端冪等」升級為強制規則,另議寫入 `code-standards.md`（所有權「❌ 先問」）。
- 檔案所有權:跨 article / infrastructure / tag 三模組,一次 PR 收斂。
- ACK 策略沿用對照組:no-op / 已處理 / 成功皆 ACK,例外才 NACK 進 DLQ（不 requeue）。
