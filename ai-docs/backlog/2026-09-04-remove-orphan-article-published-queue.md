# Backlog: 部署後手動清除既有環境殘留的 article.published 孤兒 queue

**狀態**：⏳ 待維運執行（程式面已完成，branch `chore/remove-orphan-published-queue`）
**優先級**：HIGH（此 queue 已在累積全站文章正文副本，撞到 broker flow control watermark 會阻塞所有 publisher）
**來源**：2026-09-04 稽核 ARCH-05 ＝ PERF-14

## 問題描述

`article.published`（在 `ArticleRabbitMqConfig` 中原本以 `QUEUE_PUBLISHED` 宣告）是孤兒 queue：durable、有 binding、有訊息持續進去（每次文章發布），但**從未有任何 consumer** 訂閱過它。實際的下游消費者是 search 模組（`queue.search.index`）與 recommend 模組（`recommend.article.published`）各自宣告的 queue，兩者綁定同一個 routing key `article.published`（topic exchange 會把訊息複製給每個綁定的 queue），與這個孤兒 queue 無關。

因為：

1. queue 是 durable，訊息也是 persistent（預設）。
2. payload 是完整 `ArticlePublishedEvent`，含去除 Markdown 語法後的完整文章正文（見 `ArticleEventPublisher.publishPublished`）。
3. 沒有 consumer 消費 → 訊息只進不出，無限累積。

長期下去磁碟上會堆滿全站文章正文的副本，一旦撞到 RabbitMQ 的 memory/disk watermark，broker 進入 flow control，會**阻塞所有 publisher**——不只這個孤兒 queue 的生產端，連 search/tag/version 等正常事件與發文請求本身都會被卡住。

## 程式面已完成的變更（本 branch）

- 移除 `ArticleRabbitMqConfig.articlePublishedQueue()`、`articlePublishedBinding()` 兩個 `@Bean`。
- 移除不再被引用的常數 `QUEUE_PUBLISHED`。
- **保留** `ROUTING_KEY_PUBLISHED = "article.published"`——producer（`ArticleEventPublisher.publishPublished`）仍用它發送事件給 search / recommend 模組訂閱，這個路由鍵本身沒有問題，問題只在「article 模組自己多宣告了一個沒人消費的 queue」。
- 新增守衛測試 `ArticleRabbitMqConfigTest`（`blog-module-article/src/test/java/.../config/`），以反射掃描本模組宣告的每個 `Queue` bean，斷言都能在本模組類別路徑內找到對應的 `@RabbitListener`；此測試在刪除前已確認為 RED（`articlePublishedQueue` 無 consumer），刪除後轉 GREEN。往後若有人在此模組不小心加回「宣告但無人消費」的 queue，這個測試會直接紅掉。

**重要**：應用程式重新部署後，只是「不再宣告」這個 queue（Spring 啟動時不會再對它做 `RabbitAdmin` auto-declare）。RabbitMQ **不會**因為應用程式端不宣告就自動刪除 queue——**既有環境上這個 queue 與其中已經堆積的訊息仍然存在**，需要維運手動處理。

## 需要維運執行的動作

部署本次變更後，對**每一個有跑過這支應用程式的環境**（至少：正式環境、以及任何長期存活的 staging／預發環境；本機 docker-compose 與 CI 用的 e2e 容器是一次性的，重建即清空，不需處理）執行：

### 1. 先確認 queue 內狀態，不要盲刪

```bash
# 透過 management UI 或 rabbitmqctl 確認訊息數與 consumer 數
rabbitmqctl list_queues name messages messages_ready messages_unacknowledged consumers -p <vhost>
```

預期會看到 `article.published` 的 `consumers` 欄位是 `0`（這正是孤兒 queue 的證據），`messages` 會是一個持續增長的正整數。

### 2. 確認沒有其他非本 repo 的系統仍依賴這個 queue 名稱

（理論上不應該有，`article.published` 是本 repo 內部命名，但既然要刪 production 資料，仍要求一次人工複核——不要只憑這份文件就動手。）

### 3. 刪除 queue

```bash
rabbitmqctl delete_queue article.published -p <vhost>
# 或 management UI：Queues → article.published → Delete Queue
```

若擔心裡面堆積的訊息內容需要留存（例如想確認實際堆積了多少全站正文副本、評估洩漏影響面），可先用 management UI 的 "Get messages" 功能取樣查看，或用 `rabbitmqadmin export` 匯出後再刪除。

### 4. 部署後複查

刪除後隔一段時間（例如下一次有文章發布後）再跑一次 `list_queues`，確認 `article.published` 已經**不在清單中**（而不是又被重新宣告出來——若又出現，代表還有舊版應用程式實例在跑，或有其他地方仍在宣告它，需要回頭排查）。

## 注意事項

- 此為**不可逆操作**，删除 queue 會連同其中尚未消費的訊息一併刪除。已於任務執行前複驗過沒有任何 consumer 依賴它（見 commit 訊息與 `ArticleRabbitMqConfigTest`），理論上刪除不影響任何下游功能，但仍建議由熟悉該環境的人（Yuan 或維運）在刪除前再親自用 management UI 看一眼再動手。
- DLQ（`blog.dlq` / `queue.dead-letter`）不受影響，未動。
- search／recommend 模組各自的 queue（`queue.search.index`、`recommend.article.published`）維持原樣，未受本次變更影響。

## 驗證方式（實測，不要靠推理）

部署後：

```bash
rabbitmqctl list_queues name consumers -p <vhost> | grep article.published
```

- 刪除前：預期看到一行 `article.published  0`（存在但 0 consumer）。
- 執行 `delete_queue` 後：預期完全沒有輸出（該 queue 已不存在）。
- 之後正常發布文章一篇，確認 search 索引與 recommend 快取清除仍正常運作（`queue.search.index`、`recommend.article.published` 的 `messages` 有進有出，未卡住）——證明這兩個下游 consumer 真的靠自己的 queue 運作，與被刪除的孤兒 queue 無關。
