# Implementation Plan：跨模組識別全面改為 UUID 並解除模組耦合

## Design Reference

- Design doc: `docs/plans/2026-10-24-cross-module-uuid-design.md`
- Approved by: Yuan（2026-10-24，逐段確認；同日追加 `user_preferences.user_id` 為第 18 個欄位）
- 執行模型：**Sonnet**（`model: "sonnet"`）。升級規則見 §1.3
- 修訂紀錄：v2（2026-10-24）依 fresh-context 審查修正 3 CRITICAL／7 HIGH／11 MEDIUM（事件搬遷、過渡 API、0b 合併、校正可設 NULL、重試冪等順序、指令與環境）；v3（同日）依複審修正 3 HIGH／6 MEDIUM／2 LOW（T0b-2 提交點與 controller 範圍、facade bean 循環、ArticleData 相容建構子、守衛指令加 -am、排程池、冪等與業務同交易、consumer queue 宣告）

---

## 0. 本檔怎麼用（主線 session 必讀）

### 0.1 階段總覽

| 階段 | PR | Task | 說明 |
|---|---|---|---|
| 0a | #1 | T0a-1～T0a-7 | 安全網、事件契約搬遷（只增不減）、過渡 API、守衛 |
| 0b | #2 | T0b-1～T0b-3 | 登入身分（principal 守衛先寫成 RED → 認證鏈 → 全模組替換） |
| M1 | #3 | 1～2 個 | comment |
| M2 | #4 | 2 個 | reading |
| M3 | #5 | 2 個 | version（含 `user_preferences`） |
| M4 | #6 | 1～2 個 | series |
| M5 | #7 | 1 個 | file |
| M6 | #8 | M6-a、M6-b、M6-c | article（儲存層／服務與他模組連動／事件） |
| M7 | #9 | M7-search、M7-recommend、M7-tag | 依模組拆 |
| M8 | #10 | 1 個 | user |
| Z | #11 | Z1、Z2、Z3 | 移除過渡碼／全域守衛／打開重試 |
| 並行 | 另開 | — | 公開作者功能（`docs/plans/2026-10-23-public-author-profile-design.md`），0b 合併後可與 M 階段並行，但 Flyway 版號與 facade 檔需與當下的 M 階段協調 |
| 並行 | 另開 | — | DATA-01 標籤計數（待辦 #5）：其每日**重算**排程與 T0a-3 的**孤兒校正**目的不同；若 0a 已合併，鎖與計數改用 T0a-3 的共用元件，不另寫一份 |

**一個階段 = 一個 PR，依序進行**；前一個 PR 合併進 `develop` 後才開始下一個。每個 Task 派**一個新的 Sonnet 實作 agent**，完成後派**另一個 Sonnet 審查 agent**（§1.4），主線抽查後才進下一個 Task。

M1～M8 共用 **§4 範本**；派工前主線必須先做 §4.1 的刷新盤點——前面的階段會改程式碼，本檔的行號在那時可能已失效。

### 0.2 分支

- PR 來源分支固定為 `claude/confident-cerf-324ev7`（本 session 指定分支），base 為 `develop`
- 每個 PR 合併後：`git fetch origin develop && git checkout -B claude/confident-cerf-324ev7 origin/develop`，再 `git push --force-with-lease -u origin claude/confident-cerf-324ev7`（此時分支只含已合併歷史）
- 實作 agent 使用 `isolation: "worktree"`，在自己的 worktree 內 commit；主線把 worktree 的 commit 合回指定分支，**agent 不得 push**

### 0.3 環境（2026-10-24 實測）

- **用 `mvn`，不要用 `./mvnw`**：wrapper 以 `wget` 下載 Maven 本體，`wget` 不走 proxy 而失敗；系統 `mvn` 為同版本 3.9.11
- **Docker**：daemon 預設未啟動。啟動：`(nohup dockerd > /tmp/dockerd.log 2>&1 &)`，再以 `docker info` 確認。實測 `postgres:16-alpine` 可拉取、`CrossModuleCommentIT` 4/4 通過——**IT 可在本機跑**
- `logs/` 被 `.gitignore`，新 worktree 不存在 → 先 `mkdir -p logs`
- `@DisplayName` 的中文在輸出中顯示為 `????`（container 語系），不影響結果
- Flyway 目前最高版號 V24（若 DATA-01 等其他工作先合併，版號會前進——一律以 `ls ... | sort -V | tail -1` 為準）

---

## 1. 共用規則

### 1.1 共通頭（每個實作提示詞的第一段，原文照貼）

```
Working directory: <WORKTREE_FULL_PATH>
All commands (mvn, git, file edits) MUST be run from this directory.
Do NOT operate on the main repository root.
---
你是在 Spring Boot 模組化單體 repo（blog-web-v2）中執行一個明確範圍任務的實作者。
先讀以下檔案中與本任務相關的章節（不要整份貼回來）：
- CLAUDE.md（TDD 鐵律、Test Execution Rules）
- ai-docs/code-standards.md、ai-docs/testing-standards.md、ai-docs/security.md
- ai-docs/architecture.md（跨模組邊界）、ai-docs/flyway-convention.md（若本任務含 migration）
- docs/plans/2026-10-24-cross-module-uuid-design.md（讀 §0 與你被指定的章節）

環境（已實測）：
- 一律用系統 `mvn`，不要用 `./mvnw`（wrapper 下載會失敗）
- 開工先 `mkdir -p logs`；`docker info` 不通就執行 `(nohup dockerd > /tmp/dockerd.log 2>&1 &)` 後再確認
- 首次先 `mvn -q install -DskipTests --no-transfer-progress` 讓各模組進本機 repository；
  之後若你改了上游模組（blog-common、blog-infrastructure 或被依賴的模組），先對它重新 `mvn -q install -DskipTests -pl <上游> --no-transfer-progress`
- 跑某模組測試：`mvn test -pl <module> --no-transfer-progress 2>&1 | tee logs/<task>-<red|green|baseline>.log`
- **blog-start 的 ArchUnit 守衛一律加 `-am`**（守衛掃的是各模組 class；不加 -am 會讀到 ~/.m2 裡舊的 jar 而假綠或假紅）：
  `mvn test -pl blog-start -am -Dtest=<Guard> -Dsurefire.failIfNoSpecifiedTests=false --no-transfer-progress`
- 只重跑單一類別：`mvn test -pl <module> -Dtest=<ClassName> -Dsurefire.failIfNoSpecifiedTests=false --no-transfer-progress`
- 全 reactor 編譯檢查：`mvn -q test-compile --no-transfer-progress`
- blog-common、blog-infrastructure、blog-module-user 三個模組的 pom **沒有** `*IT.java` 的 surefire includes——
  在這三個模組寫整合測試時命名為 `*IntegrationTest`（沿用 UserFacadeIntegrationTest 先例），否則測試永遠不會被執行

硬規則：
1. TDD：先寫失敗測試並實際跑出紅燈（存 log），才寫實作；再跑出綠燈（存 log）。
2. 單一失敗先讀 `<module>/target/surefire-reports/TEST-*.xml`，只重跑受影響的類別；永不為單一失敗重跑全套。
3. 不弱化、不刪除、不 @Disabled 任何既有測試；需要修改既有測試時，在回報中逐一說明原因。
4. 不碰已套用的 migration 檔；新增 migration 用下一個未使用的版號
   （`ls blog-db-migration/src/main/resources/db/migration | sort -V | tail -1`），並同步 ai-docs/schema.md。
5. 所有新類別與公開方法要有繁體中文 JavaDoc；測試方法名稱與 @DisplayName 遵守 testing-standards.md。
6. IT 需要 Docker。若嘗試啟動 dockerd 後仍不可用：照樣寫 IT 並確認可編譯，在回報中明確寫「IT 未在本機執行（無 Docker）」——絕不可把沒跑寫成通過。
7. 在 worktree 內以 Conventional Commits、繁體中文 subject 提交（ai-docs/git-convention.md），不要 push。
8. 做不到就停下來回報卡在哪，不要交「看起來像完成」的半成品。
```

### 1.2 共通回報格式（每個實作提示詞的最後一段，原文照貼）

```
【回報格式】（結論先行，總長 ≤ 60 行；長產物寫檔回傳路徑）
1. 一句話結論：完成／部分完成／未完成（原因）
2. 測試場景表：class | method | 場景 | RED 證據（log 檔:行） | GREEN 結果
3. 變更檔案清單：path（新增/修改/刪除）＋ 一句話
4. 驗收條件逐條勾選，每條附證據（指令輸出、file:line、surefire XML 路徑）
5. 修改了哪些既有測試、為什麼
6. 未確認／風險清單（含 IT 是否實際在本機執行）
7. commit hash 清單
```

### 1.3 升級與止損

- 同一 Task，Sonnet 實作連續兩次未通過驗收 → 帶完整失敗軌跡（兩次的提示詞、回報、驗收失敗原因）改派 Opus（`model: "opus"`）
- Opus 仍不過 → 停下，整理失敗軌跡問 Yuan
- **T0b-2、T0b-3 若第一次就出現漏改或編譯不過**，直接升 Opus

### 1.4 審查提示詞（每個 Task 完成後派，fresh context，Sonnet）

```
Working directory: <WORKTREE_FULL_PATH>
---
【目標與動機】從反方立場審查 Task <ID> 的變更（`git diff <base>..HEAD`），任務描述如下：
<貼上該 Task 的「目標」與「驗收條件」>
你的工作是找出它**沒做到**或**做錯**的地方，不是確認它做完了。
【檢查清單】
- 驗收條件逐條自行驗證（自己跑指令／grep，不採信實作者的回報）；指令一律用 `mvn`
- ai-docs/judgment.md §2 危險模式訊號表逐條 grep
- code-standards.md（Transaction + MQ 時序、Error Handling）、security.md（原則 1/7/8/10）
- architecture.md 跨模組邊界；設計 §0「不變」清單是否被違反（模組自有主鍵、模組內部關聯、刪除語意、對外 API）
- 測試：RED 是否真的紅過（看 log）；是否只斷言 mock 自己的回傳（套套邏輯）；是否弱化既有測試；
  整合測試是否放在不會被執行的命名（blog-common／infrastructure／user 的 *IT.java）
- 自我驗證矩陣是否漏了最難的那一格（institution-notes.md 2026-09-05）
【驗收條件】每條 finding 附 file:line＋違反的規範或驗收條件＋建議修法；沒有 finding 也要列出「檢查過什麼、怎麼查的」。
【回報格式】severity 表（CRITICAL/HIGH/MEDIUM/LOW），不確定的標 PLAUSIBLE；≤ 50 行。
```

---

## 2. 階段 0a：安全網、事件契約與過渡 API（PR #1）

> 不改變任何模組的業務行為。完成後：所有 listener 有計數、有校正排程框架、跨模組事件在共用位置且帶 uuid＋eventId（Long 欄位暫留）、owner DTO 有 UUID 欄位、兩條帶例外清單的守衛。

### Task T0a-1 — blog-infrastructure/MQ：consumer 共用包裝

- **相依**：無

```
<§1.1 共通頭>
【目標與動機】
9 個檔案、12 個 @RabbitListener 方法各自手寫 try { ... basicAck } catch { basicNack(requeue=false) } 樣板（findings ARCH-02、roadmap C1），
沒有任何失敗計數——訊息進 DLQ 沒有人知道。本任務只建立共用包裝，T0a-2 才套用。
在 blog-infrastructure 新增 `ConsumerExecutor`（套件 dowob.xyz.blog.infrastructure.mq）：
- `void execute(String consumerName, Channel channel, long deliveryTag, ThrowingRunnable body)`
- 行為**與現行樣板完全一致**：body 成功 → basicAck；body 拋例外 → log.error ＋ basicNack(deliveryTag, false, false)；
  nack 本身 IOException → log.error，不往外拋
- Micrometer 計數（比照 blog-infrastructure 的 BatchedQuery：全域 registry 未掛子 registry 時不得報錯）：
  `mq.consumer.processed{consumer}`、`mq.consumer.failed{consumer}`、`mq.consumer.dead_lettered{consumer}`（requeue=false 的 nack 即 dead_lettered）
- **不得**打開重試、不得改成拋例外給 retry interceptor（設計 §3.1：收尾階段才打開）
【範圍】只動 blog-infrastructure。
【驗收條件】
- ConsumerExecutorTest：成功 ack＋processed；失敗 nack(requeue=false)＋failed＋dead_lettered；nack IOException 不外拋；registry 無子 registry 不報錯
- RED、GREEN log 存在；`git diff --stat` 只含 blog-infrastructure
<§1.2 共通回報格式>
```

### Task T0a-2 — 各模組 consumer：套用共用包裝（重構）

- **相依**：T0a-1

```
<§1.1 共通頭>
【目標與動機】把所有 @RabbitListener 方法改用 ConsumerExecutor，這是**純重構**。
以 `grep -rn "@RabbitListener" --include=*.java blog-*/src/main` 為準（2026-10-24：9 個檔案、12 個方法，其中
blog-module-search/.../listener/ArticleSearchListener.java 有 4 個；grep 也會命中 IdempotencyService 的 JavaDoc，忽略）。
【前置】先 `mvn -q install -DskipTests` 後，跑所有受影響模組的測試建立綠基線，存 logs/T0a-2-baseline.log。
【禁區】ack/nack 時機、IdempotencyService 的位置與語意（「冪等命中 → ack 並 return」必須保留在 body 內且仍然 ack）、
log 關鍵資訊、MQ 訊息格式全部凍結；不新增功能、不改 queue 設定。
【驗收條件】
- 基線綠 → 重構後同樣綠（兩份 log）
- `grep -rn "basicNack\|basicAck" --include=*.java blog-module-*/src/main` 零命中（無法收斂的特殊 ack 邏輯保留並逐一說明）
- 既有 consumer 測試若直接 verify(channel).basicAck，改為透過真實 ConsumerExecutor 驗證，不得 mock 掉 executor 後只驗呼叫
<§1.2 共通回報格式>
```

### Task T0a-3 — blog-infrastructure/Job：孤兒校正排程框架

- **相依**：無

```
<§1.1 共通頭>
【目標與動機】
設計 §0 目標 2：跨模組 FK 拿掉後，連動改靠事件；事件遺失時要有每日校正修正孤兒並留下計數。本任務只建框架。
新增抽象類 `OrphanReconciliationJob`（套件 dowob.xyz.blog.infrastructure.reconciliation）：
- 子類別提供：`String module()`、`String table()`、
  `List<UUID> fetchReferencedUuids(UUID afterExclusive, int limit)`（keyset 分頁）、
  `Set<UUID> filterExisting(Collection<UUID>)`（委派 owner facade）、
  `int removeOrphans(Collection<UUID> missing)`——**依欄位語意刪除列或把參照設為 NULL**（JavaDoc 必須寫明兩種語意都合法；
  例：articles.series_uuid 為設 NULL，絕不可刪文章）
- 框架負責：Redis 分散式鎖（參考 blog-module-recommend/.../job/TrendingRefreshJob.java 的 setIfAbsent＋TTL，
  但**解鎖改為 token compare-and-delete**（Lua 或 RedisScript），避免刪到別人的鎖；鎖 key 含 module 與 table）、逐頁處理、
  計數 `reconciliation.orphans_removed{module,table}`、`reconciliation.runs{module,table,result}`、每次一行摘要 log
- owner facade 拋例外 → 整次中止、零修改、result=failed（fail-safe）
- 排程由子類別以 @Scheduled 觸發；框架提供 `run()`。排程池目前為單執行緒（findings PERF-16），而既有 flush／trending job 是 fixedDelay、
  沒有固定時段可錯開——本 Task 在 application.yaml 設定 `spring.task.scheduling.pool.size: 4`（附測試或 context 驗證），
  並在框架加「單次執行處理頁數上限」（預設值寫成常數，超過即結束本次、下次續跑），避免長掃描佔住執行緒
【範圍】只動 blog-infrastructure。
【驗收條件】
- 測試：取鎖失敗 → 不處理；鎖以 token 解鎖（他人的鎖不被刪）；多頁走訪；只處理不存在者；filterExisting 拋例外 → 零修改＋result=failed；計數與 log
- 以 src/test 的測試子類別驗證，不得在 src/main 新增具體子類別
<§1.2 共通回報格式>
```

### Task T0a-4 — Facade：owner 端存在性查詢

- **相依**：無

```
<§1.1 共通頭>
【目標與動機】校正排程需要問 owner「這些 UUID 哪些還存在」。新增：
- `ArticleFacade.filterExistingUuids(Collection<UUID>)`：仍存在的文章 uuid（**不分狀態**，草稿也算）
- `SeriesFacade.filterExistingUuids(Collection<UUID>)`
介面在 blog-infrastructure/.../facade，實作在各自模組。比照 `ArticleFacadeImpl.filterPublishedUuids`：
null／空輸入不發查詢、去重、經 `BatchedQuery` 切批。
【範圍】2 個介面方法＋article、series 實作與 mapper 查詢；不改任何既有方法。
【驗收條件】
- 單元測試：空輸入不查 DB、去重、結果正確
- IT（真 Postgres）：草稿、已發布、已刪除各一筆 → 只回前兩者；超過一個批次大小的輸入正確切批
- JavaDoc 標明「不分狀態」與 filterPublishedUuids 的差異
<§1.2 共通回報格式>
```

### Task T0a-5 — 事件契約：跨模組事件搬到共用位置（只增不減）

- **相依**：T0a-2、**T0a-6**（需要 T0a-6 的過渡 API 取得 authorUuid／seriesUuid）

```
<§1.1 共通頭>
【目標與動機】設計 §3.1「事件契約位置」。M1～M7 各模組要訂閱 article／series 的事件，但事件類別與 exchange／routing key
常數現在在 article 模組（blog-module-article/.../config/ArticleRabbitMqConfig.java、.../event/），他模組只能直接 import——
這正是要消滅的耦合。本任務把**跨模組事件**搬到 blog-infrastructure/.../event，常數搬到同一處的契約類別
（例如 `ArticleEventContract`：EXCHANGE="article.events"、各 ROUTING_KEY_*，字串值不得改變）。
跨模組事件 = 有他模組 consumer 者（以實際 grep 為準）：ArticleDeletedEvent（series；search 用鏡像 DTO）、
ArticleContentChangedEvent（version）、ArticleUpdatedEvent／ArticleArchivedEvent／ArticlePublishedEvent（search、recommend 用鏡像 DTO 或直接用）、
ArticleTagEvent（tag；現有兩份重複定義，findings ARCH-01——收斂為一份）。
模組內部事件（ArticleViewedEvent、ImageUploadedEvent、UserRegisteredEvent、UserPasswordResetRequestedEvent）**不搬**。
**只增不減**：
- 每個搬移的事件加上 `UUID eventId`（若沒有）與對應的 uuid 欄位（`authorUuid`、`seriesUuid` 等；articleUuid 已有者不重複加）
- 既有 Long 欄位**保留**（過渡欄位，JavaDoc 標「將於 M<n> 移除」），發送端同時填 Long 與 uuid
- 發送端（article）與所有接收端改 import 新位置；接收端邏輯不變（仍讀 Long 欄位）
- search 的鏡像 DTO（listener/dto/*Message）**本 Task 不動**，只需確認 JSON 欄位相容（新增欄位不影響反序列化）
- authorUuid／seriesUuid 的取得：沿用 T0a-6 在 article 模組建立的取得方式（含其 bean 循環處理），不得 JOIN series 表
【禁區】exchange 名稱、routing key、queue 宣告、訊息既有欄位的名稱與型別凍結；不改任何 consumer 的業務邏輯。
【驗收條件】
- 每個搬移事件的序列化測試：新 JSON 含原有全部欄位（名稱型別不變）＋ eventId ＋ 新 uuid 欄位
- 既有 consumer 測試全綠；`grep -rn "import dowob.xyz.blog.module.article.event\|ArticleRabbitMqConfig" --include=*.java blog-module-*/src/main | grep -v blog-module-article` 零命中
<§1.2 共通回報格式>
```

### Task T0a-6 — 過渡 API：owner DTO 補 UUID 欄位

- **相依**：無（**必須先於 T0a-5**）

```
<§1.1 共通頭>
【目標與動機】設計 §3.1「過渡 API」。各模組階段要改用 uuid，但 owner 的 facade DTO 只有 Long：
`ArticleData(id, uuid, authorId, status, seriesId, seriesPosition)` 沒有 authorUuid／seriesUuid；
comment、series、version 的授權判斷用 `article.authorId().equals(userId)`（例 CommentService、SeriesService、VersioningService）；
`ArticleVisibility.isReadableBy`（blog-common）只吃 Long。M4 需要以 series uuid 查文章，但 articles 只有 series_id。
本任務只**新增**：
1. `ArticleData` 加 `authorUuid`、`seriesUuid`。為避免破壞他模組的建構呼叫與測試 mock：**保留原本 6 參數的建構子**（委派到新的 canonical 建構子、新欄位為 null，
   JavaDoc 標「僅供過渡期既有呼叫端，Z1 移除」）；**article 模組內產生 ArticleData 的地方一律改用新建構子並填值**（加測試確保 facade 回傳的值非 null）。
   批次方法（findByIds 等）的 authorUuid／seriesUuid 必須**一次批次取得**，不得逐筆查詢。值以下列方式取得
   - authorUuid：article 模組內 users 仍是 reference data，可 JOIN users 取 uuid（M6 改為 author_uuid 欄位）
   - seriesUuid：經 `SeriesFacade` 批次把 series id 轉 uuid（新增 `Map<Long, UUID> findUuidsByIds(Collection<Long>)`），**不得 JOIN series 表**（CrossModuleBoundaryTest 會擋）。
     ⚠️ **bean 循環**：`SeriesFacadeImpl` 已建構子注入 `ArticleFacade`；`ArticleFacadeImpl`（以及 T0a-5 的 `ArticleEventPublisher`）再注入 `SeriesFacade` 會形成循環。
     一律以 `ObjectProvider<SeriesFacade>`（延遲取得）注入，不得用 field injection 或 `allow-circular-references`
2. `SeriesFacade.findIdByUuid(UUID)`：供 M4～M6 過渡期 article 以 series uuid 查詢
3. `ArticleVisibility.isReadableBy` 的 UUID 版 overload（語意與 Long 版完全相同）
4. 其他 M1～M4 會用到、目前只有 Long 版的 owner DTO 欄位（以 grep `authorId()` 的跨模組使用點盤點），一律只增不減
【禁區】不刪不改任何既有欄位與方法；不改業務邏輯。
【驗收條件】
- 新欄位的值有測試（authorUuid 與 users.uuid 一致、seriesUuid 與 series.uuid 一致、無系列時為 null）
- `mvn -q test-compile` 全 reactor 通過；受影響模組測試全綠
- **Spring context 能啟動**：`mvn test -pl blog-start -am -Dtest=ContextSmokeTest -Dcontext.smoke=true -Dsurefire.failIfNoSpecifiedTests=false --no-transfer-progress` 綠（驗證沒有 bean 循環）
<§1.2 共通回報格式>
```

### Task T0a-7 — blog-start/Architecture：事件契約與跨模組 import 守衛

- **相依**：T0a-5、T0a-6
- **驗證**：`mvn test -pl blog-start -am -Dtest='EventContractTest,CrossModuleImportTest' -Dsurefire.failIfNoSpecifiedTests=false --no-transfer-progress`

```
<§1.1 共通頭>
【目標與動機】設計 §3.1。兩條 ArchUnit 守衛，以「例外清單」起步，各模組階段逐步清空，收尾時必須為空。
參考 blog-start/src/test/java/dowob/xyz/blog/architecture/EndpointAuthorizationTest.java（死條目防護）與 CrossModuleBoundaryTest.java。
1. EventContractTest：所有事件類別（類名以 Event 結尾、位於 `..event..` 套件；含 blog-module-user 的 model.event）
   (a) 必須有 `java.util.UUID eventId`
   (b) 不得有 `Long`/`long` 欄位或 record component
   (c) **跨模組事件**（T0a-5 搬移的那些）必須位於 `dowob.xyz.blog.infrastructure.event..`；模組內部事件不受 (c) 約束
   三條各自一份例外清單（類別全名，或「類別#欄位」）
2. CrossModuleImportTest：`dowob.xyz.blog.module.<A>..` 不得依賴 `dowob.xyz.blog.module.<B>..`（A≠B）；例外清單以「來源類別 → 目標類別」為單位
3. 兩者都要有：防死條目測試、反向驗證（src/test fixture 證明守衛抓得到違規）
【驗收條件】
- 現況全綠；刪掉任一例外條目 → 對應測試紅（回報附輸出，之後還原）
- 每個例外條目有一行註解寫明預計在哪個階段（M1～M8／Z）清除
<§1.2 共通回報格式>
```

---

## 3. 階段 0b：登入身分（PR #2）

> 風險最高：principal 型別一換，**漏改的地方不會編譯失敗，而是靜默變成「未登入」**——`@AuthenticationPrincipal Long` 拿到 null；
> `ArticleResponseMapper.java:288` 的 `getPrincipal() instanceof Long` 變 false。所以先寫守衛（RED），再改。
> T0b-2 完成到 T0b-3 完成之間程式**不可部署**（controller 仍收 Long）——三個 Task 都在同一個 PR，合併時已一致。

### Task T0b-1 — blog-start/Architecture：principal 型別守衛（先寫成 RED）

- **相依**：0a 已合併

```
<§1.1 共通頭>
【目標與動機】T0b-3 的漏改不會造成編譯失敗，必須先有守衛。新增 ArchUnit `PrincipalTypeTest`（只掃 src/main 的類別）：
(a) 標註 @AuthenticationPrincipal 的參數型別必須是 `AuthUser`（本 Task 先在 blog-common 建立 `AuthUser(Long id, UUID uuid, Role role)`
    record 讓守衛能引用；id 的 JavaDoc 標「過渡欄位，收尾階段移除，新程式碼一律用 uuid()」），
    並在 blog-common 的 `SecurityUtils` 新增 `Optional<AuthUser> currentAuthUser(Authentication)`（T0b-3 會用；本 Task 只建立＋單元測試）
(b) 除白名單（SecurityUtils、JwtAuthenticationFilter、AuthStateLoader 所在類別）外，不得呼叫 `Authentication.getPrincipal()`
(c) 反向驗證 fixture（放 src/test）：Long 型別的 @AuthenticationPrincipal、白名單外呼叫 getPrincipal()，兩者都被抓到
【驗收條件】
- 本 Task 完成時守衛**必須是紅的**（現況 63 處 Long principal、2 處 getPrincipal()），回報附紅燈輸出中的違規數
- 反向驗證 fixture 測試綠
- 驗證指令：`mvn test -pl blog-start -am -Dtest=PrincipalTypeTest -Dsurefire.failIfNoSpecifiedTests=false --no-transfer-progress`
<§1.2 共通回報格式>
```

### Task T0b-2 — 認證鏈：JWT、AuthStateLoader、filter、user 模組（含 Q9）

- **相依**：T0b-1
- **升級**：第一次出現編譯不過或測試漏改即升 Opus

```
<§1.1 共通頭>
【目標與動機】設計 §3.2＋Q9。以下必須在同一個 Task 完成，拆開會無法編譯。**Task 內分 3 個提交點，每個提交點都必須 `mvn -q test-compile` 全 reactor 綠，並各自 commit**：
- 提交點 1：第 1 項（JWT）＋ RedisKeyConstant 改收 UUID ＋ user 模組的呼叫端（第 5 項）
- 提交點 2：第 2、3 項（UserAuthService UUID 版＋AuthStateLoader）
- 提交點 3：第 4、6 項（filter、search）＋ 兩個 controller（見第 8 項）
1. JwtService：access／refresh token 的 subject 改為使用者 uuid；`getUserIdFromToken` 改為 `getUserUuidFromToken` 回傳 UUID；
   subject 為數字的舊 token 一律無效（Yuan 決定直接切換）
2. `UserAuthService`（blog-infrastructure 介面，user 模組 `UserAuthServiceImpl` 實作，目前只有 Long 版）新增以 uuid 查詢認證狀態的方法並實作
3. `AuthStateLoader`（blog-infrastructure）：輸入 uuid，回傳 {id, uuid, status, role, tokenVersion}；Redis key `user:auth:{uuid}`
   （RedisKeyConstant.getUserAuthKey 改收 UUID），hash 新增 id 欄位；**hash 缺任一必要欄位（含 id）一律視為 miss**，查 DB 回填。
   loader 只負責取資料；「哪些 status 可通過」的政策**留在呼叫端並維持現狀**（filter 現行接受的狀態、refresh 要求 ACTIVE，兩者不得改變）
4. JwtAuthenticationFilter 改用 loader，principal 改為 AuthUser。authorities 計算方式不變；**AuthUser.role 與 authorities 同源**（維持現行：取自 token 的 role claim），不得因改用 loader 而改變角色來源
5. user 模組：
   - AuthService.login：寫 auth hash 時**必須寫入 id**（現在 AuthService.java 約 221-224 行只寫 version/status/role），key 改 uuid
   - AuthController.refresh：改用 AuthStateLoader（修 SEC-26：Redis miss 不再誤判 ACCOUNT_SUSPENDED）；refresh ZSet key 改 uuid
   - logout、裝置數修剪、登入失敗計數、SessionRevoker：Redis key 全改 uuid（RedisKeyConstant 對應方法改收 UUID）；撤銷仍在 afterCommit
6. 搜尋紀錄 key（getSearchHistoryKey）改收 UUID；呼叫端 SearchServiceImpl 的簽章在本 Task 改為收 UUID（controller 的傳值在 T0b-3 處理）
7. 這兩個模組的整合測試命名為 `*IntegrationTest`（見共通頭）
8. **例外：本 Task 一併把 `AuthController`（logout 等）與 `SearchController` 的 `@AuthenticationPrincipal Long` 改為 `AuthUser`**，
   傳 `user.uuid()` 給已改收 UUID 的 service——否則第 5、6 項的簽章變更會讓這兩個 controller 編譯不過
【禁區】token version 比對、status 檢查、authorities 計算語意不變；security.md 原則 10 不得破壞；除第 8 項的兩個 controller 外，不改各模組 controller 的 principal（T0b-3）。
【驗收條件】
- `mvn -q test-compile` 全 reactor 通過
- RED→GREEN 測試至少涵蓋：subject 為 uuid；數字 subject 無效；loader 命中不查 DB；hash 缺 id 視為 miss 並回填；DB 查不到回 empty；
  login 寫入的 hash 含 id；Redis auth hash 被刪後 refresh 成功並回填（SEC-26）；非 ACTIVE 仍被 refresh 拒絕；撤銷刪除的是 uuid 版 key
- `mvn test -pl blog-common,blog-infrastructure,blog-module-user,blog-module-search --no-transfer-progress` 全綠（log）
- 3 個提交點各自的 `test-compile` 輸出（log）
<§1.2 共通回報格式>
```

### Task T0b-3 — 全模組：principal 型別替換

- **相依**：T0b-2
- **升級**：第一次出現漏改（PrincipalTypeTest 未轉綠、grep 有殘留）即升 Opus

```
<§1.1 共通頭>
【目標與動機】設計 §3.2。principal 已是 AuthUser，T0b-1 的守衛目前是紅的。把所有 `@AuthenticationPrincipal Long xxx` 改為
`@AuthenticationPrincipal AuthUser user`（公開端點維持可為 null），方法內原本用 Long 的地方改用 `user.id()`
（可為 null 處 `user == null ? null : user.id()`）。這是**純型別替換**，業務邏輯照舊用 Long id。
（AuthController、SearchController 已在 T0b-2 改完，本 Task 不再處理。）
直接讀 principal 的兩處改用 T0b-1 建立的 `SecurityUtils.currentAuthUser(...)`：ArticleResponseMapper.java:288、ArticleQueryService.java:301。
測試：各測試類別的 asUser(Long, Role) 類 helper 改為放入 AuthUser（固定測試 UUID，id 維持原值）；約 31 個測試檔。
分布（2026-10-24）：@AuthenticationPrincipal 63 處——article 2 檔、comment 2、file 1、reading 4、search 1、series 1、tag 1、user 2、version 2。
逐模組處理，每個模組改完就跑該模組測試。
【禁區】不改業務邏輯、SQL、facade；不弱化任何斷言。
【驗收條件】
- PrincipalTypeTest 轉綠
- `grep -rnE "@AuthenticationPrincipal\s+(final\s+)?Long" --include=*.java blog-*/src/main` 零命中
- `mvn test --no-transfer-progress 2>&1 | tee logs/T0b-3-full.log` 全綠（本 Task 碰所有模組，允許跑全套一次）
<§1.2 共通回報格式>
```

---

## 4. 階段 M1～M8：模組提示詞範本

### 4.1 派工前刷新（主線做，不派 agent）

在最新 develop 上：

1. `grep -rnE "\b(user_id|author_id|uploader_id|article_id|series_id)\b" blog-module-<m>/src/main` → Long 欄位與 SQL
2. `grep -rnE "\bLong\b[^;]*\b(user|author|article|series|viewer|operator|currentUser)Ids?\b|<Long>" blog-module-<m>/src/main` → 參數與集合
3. `grep -rniE "join users|from users" blog-module-<m>/src/main` → 直接 JOIN users（owner 模組 user 本身除外）
4. 守衛例外清單（EventContractTest、CrossModuleImportTest）中屬於本模組的條目
5. 本模組呼叫的 owner facade 方法中尚無 UUID 版者
6. 本模組的表在 `pg_indexes` 中涉及要 DROP 欄位的索引與 UNIQUE（從 schema.md 與 migration 盤點）
7. 本模組的 IT 用哪個 `*TestApplication`、掃描了哪些他模組、`R__*_seed.sql` 中 users 的 uuid 是否固定
8. findings 中本模組待併入的 T2 條目（設計 §5）
9. 下一個 Flyway 版號；**再次確認仍無正式環境**（有則依設計 §4 改回雙寫，停下問 Yuan）

### 4.2 模組實作提示詞範本

```
<§1.1 共通頭>
【目標與動機】
設計 docs/plans/2026-10-24-cross-module-uuid-design.md §4（標準流程）與 §5 第 <N> 列（<模組>）。
把 <模組> 中所有指向其他模組的 Long 識別改為 UUID，並解除它對其他模組的資料庫與程式碼耦合。做完後本模組：
- 表中沒有指向其他模組的 Long 欄位、沒有跨模組 FK
- 程式中沒有其他模組的 Long id（參數、DTO 欄位、事件欄位）
- 不 JOIN 其他模組的表（含 users），不 import 其他模組的類別
- 原本由 FK CASCADE／SET NULL 處理的連動，改為訂閱 blog-infrastructure/.../event 的共用事件，並有每日校正兜底

【盤點結果】（主線依 §4.1 填入）
<…>

【本模組特別事項】（設計 §5 第 <N> 列原文＋§4.3 補充）
<…>

【流程】
1. 基線：`mvn test -pl blog-module-<m> --no-transfer-progress 2>&1 | tee logs/<task>-baseline.log`
2. RED：先寫失敗測試，至少涵蓋
   - repository／mapper 以 uuid 讀寫（IT）
   - **回填 IT**：比照 blog-module-comment 的 CommentArticleUuidMigrationIT——Flyway 固定在 V<N-1>、灌資料、再跑 V<N>，斷言 uuid 回填正確、舊欄位已移除
   - **寫入時的存在性檢查仍成立**：對不存在的 article／series uuid 寫入 → 仍回 404 且不落任何列（FK 拿掉後這是唯一防線）
   - 刪除連動（僅限原本有 FK CASCADE／SET NULL 者）：收到共用事件後資料被刪除或設 NULL；重複收到同一事件不出錯
   - 校正排程子類別：孤兒被移除、非孤兒保留（IT）
   - T2 條目：同一請求重複兩次 → 回 200 且計數只變一次（IT，真 Postgres，不得 mock 約束）
   - JOIN users 改批次後：一頁 N 筆只呼叫一次 UserFacade 批次方法（verify times(1)）；**已刪除或不存在使用者的作者欄位語意與現在相同**（現行 LEFT JOIN 的結果）
3. Migration V<下一版號>__<描述>.sql：
   新增 <x>_uuid UUID → 由 owner 表回填（UPDATE ... FROM users/articles/series；owner 列不存在的孤兒列先依刪除語意處理並在 migration 中 RAISE NOTICE 記錄筆數）
   → SET NOT NULL（原欄位可為 NULL 者維持可 NULL）→ 建涵蓋所有列的 <x>_uuid 索引 → DROP 舊 Long 欄位（連同 FK）→
   **依 §4.1 第 6 項的清單逐一重建**被 DROP 連帶刪除的索引、partial index、UNIQUE（欄位改為 uuid、順序與原本一致）。
   **新 uuid 欄位不建 FK。** 回報附 migration 前後 `SELECT indexname, indexdef FROM pg_indexes WHERE tablename IN (...)` 對照表。
   同步 ai-docs/schema.md（欄位、索引、FK、Migration Index）。授權範圍：設計 §4「Migration 授權」；超出者（模組自有主鍵、模組內部關聯）禁止。
4. entity／mapper／repository 改 uuid；T2 改 `INSERT ... ON CONFLICT (<與 UNIQUE 同順序的欄位>) DO NOTHING`，mapper 回傳 int，
   依 affected rows（0 或 1）決定是否更新計數。範例：
     @Insert("INSERT INTO user_article_likes (user_uuid, article_uuid, created_at) VALUES (#{userUuid}, #{articleUuid}, now()) " +
             "ON CONFLICT (user_uuid, article_uuid) DO NOTHING")
     int insertIfAbsent(@Param("userUuid") UUID userUuid, @Param("articleUuid") UUID articleUuid);
     // service：if (mapper.insertIfAbsent(u, a) == 1) articleFacade.incrementLikeCount(a);
   （注意：ReadingProgressMapper 的 DO UPDATE 恆回 1，不適用於計數判斷）
5. service／controller 改用 `AuthUser.uuid()`；呼叫 owner facade 的 UUID 版。owner 缺 UUID 版時可在本 Task 於 owner 模組**新增**
   （介面＋實作＋測試），**不得刪除或修改既有 Long 版**。
6. JOIN users → `UserFacade` 批次方法（缺就新增 UUID 版，例如 getAuthorInfoByUuids），一頁只查一次。
7. 移除本模組對其他模組的直接 import；本模組使用的事件改讀 uuid 欄位；本模組**發送**的事件移除 Long 過渡欄位（接收端同步改讀 uuid）。
   從 EventContractTest、CrossModuleImportTest 的例外清單刪除本模組條目。
8. 刪除連動 consumer（ConsumerExecutor 包裝；在本模組的 RabbitMqConfig 宣告 queue〔命名沿用 `<模組>.<事件>` 慣例〕、DLQ 參數沿用既有 `dlqArgs` 寫法、
   binding 一律使用 T0a-5 共用契約的 exchange／routing key 常數）＋ OrphanReconciliationJob 子類別（每日一次）——
   **只針對原本有 FK CASCADE／SET NULL 的參照**；原本就沒有連動的（例如 file_metadata.article_uuid，V20 刻意無 FK）不得新增連動。
9. 若本模組的 IT 透過 *TestApplication 掃描了他模組的真實碼，或 R__*_seed.sql 的 users uuid 為隨機值：以固定 uuid 修正 seed，並跑那些 IT。
10. 跑本模組與所有被動到的模組測試（綠燈 log）。

【禁區】
- 對外 API 的 URL、請求、回應 JSON 格式不得改變；搬移或改名 response DTO 時**保持類別簡單名稱不變**（OpenAPI schema 名稱取自它），
  並以 `curl localhost:<port>/v3/api-docs` 或既有 OpenAPI 測試比對該 schema 前後相同
- 刪除語意不得改變
- 對 owner 模組只允許「新增 UUID 版方法」
- 不做雙寫

【驗收條件】
- 設計 §7.1 全部項目
- grep 證據（貼在回報中）：
  `grep -rnE "\b(user_id|author_id|uploader_id|article_id|series_id)\b" blog-module-<m>/src/main` 只剩模組內部關聯
  `grep -rniE "join users|from users" blog-module-<m>/src/main` 零命中
  `grep -rn "import dowob.xyz.blog.module\." blog-module-<m>/src/main | grep -v "module\.<m>\."` 零命中
- 例外清單確實縮短（附 diff）；pg_indexes 對照表
<§1.2 共通回報格式>
```

### 4.3 各模組補充（填入範本的「本模組特別事項」）

| 階段 | 模組 | 建議切法 | 補充 |
|---|---|---|---|
| M1 | comment | 1 個 Task（可再拆：儲存層／服務） | `comments.article_uuid` 已存在且雙寫（V24）：DROP `article_id` 與 `comments_article_uuid_fkey`、移除雙寫；`comment_likes.comment_id`、`comments.parent_id` 是模組內部關聯，維持 Long。留言數加減在 ArticleFacade 新增 UUID 版。授權判斷改用 T0a-6 的 `ArticleData.authorUuid` |
| M2 | reading | 2 個：4 張表的儲存層／服務與 facade | 閱讀進度 Redis key 目前 `userId:articleUuid`（ReadingProgressFlushJob 以 parseLong 解析），改 uuid（無正式環境，舊 key 不遷移）；`getSummariesByUuids` 回傳的 DTO 放 facade dto，reading 回應 JSON 逐欄位與現在相同（加測試比對）；ReadingFacade 6 個方法改 UUID |
| M3 | version | 2 個：`article_versions`／`user_preferences` 與渲染職責 | `user_preferences.user_id`（→ users，CASCADE）為第 18 個欄位：轉 `user_uuid`、拿掉 FK（使用者只軟刪除，CASCADE 從未觸發，行為不變）。restore 改由 article 渲染後，VersionControllerIT 既有的凍結守衛與權限案例必須仍成立 |
| M4 | series | 1～2 個 | 發送 `SeriesDeletedEvent`（放 infrastructure/event，帶 seriesUuid＋eventId）；**article 端的接收與清空 `series_uuid` 留到 M6**——本階段 `articles.series_id` 的 FK `ON DELETE SET NULL` 仍在，行為不變。以 series uuid 查文章用 T0a-6 的過渡 API |
| M5 | file | 1 個 | DROP `files` 整張表前再 grep 一次零讀寫並附在回報；`file_metadata.article_uuid` 不新增刪除連動 |
| M6 | article | **M6-a** 儲存層：`author_uuid`、`series_uuid` migration、mapper（含推薦 XML）、index 重建（含 partial `idx_articles_series_position`）／**M6-b** 服務與連動：訂閱 `SeriesDeletedEvent` 清空 series_uuid（OrphanReconciliationJob 子類別的 removeOrphans 為**設 NULL**，絕不可刪文章）、`countPublishedByAuthor`、`getPublishedStatsByAuthor` 內部改用 author_uuid／**M6-c** 事件：移除 article 所發事件的 Long 過渡欄位，並**同 PR 最小修改** search 鏡像 DTO（listener/dto/*Message）與 recommend consumer 改讀 uuid | M6 會連動 comment、reading、series、version 的 IT（各模組 *TestApplication 掃描 article 真實碼、seed 資料）——每個 Task 都要跑這 4 個模組的 IT |
| M7 | search／recommend／tag | **M7-search**：ES 文件改存 `author.uuid`、移除 `id` 與 `username`，PR 說明寫明部署後要執行 admin reindex；搜尋結果拿掉逐頁批次作者查詢／**M7-recommend**：剩餘 Long 與 JOIN users／**M7-tag**：DROP `article_tags` → `articles(uuid)` 的 FK、改事件刪除＋校正；RACE-08 | 三個 Task 各自獨立 |
| M8 | user | 1 個 | 模組內事件（註冊、重設密碼）改 uuid＋eventId；`UserFacade` 全面 UUID；若 Q8 尚未完成，RACE-02/03 在此處理。驗收的 `from users` grep **排除 user 模組自己的 UserMapper** |

---

## 5. 階段 Z：收尾（PR #11）

### Task Z1 — 移除過渡碼

```
<§1.1 共通頭>
【目標與動機】設計 §6。所有模組已改造完成。移除 AuthUser.id 與所有使用點；移除 facade 所有 Long 版方法與 DTO 的 Long id 欄位
（ArticleData.id、authorId、seriesId、ArticleContentData.id 等）；移除事件中殘留的 Long 過渡欄位。以編譯錯誤為導引。
【驗收條件】`mvn test --no-transfer-progress` 全綠（允許全套一次）；`grep -rn "\.id()" ` 中 AuthUser 相關零命中；PrincipalTypeTest 仍綠
<§1.2 共通回報格式>
```

### Task Z2 — 全域守衛

```
<§1.1 共通頭>
【目標與動機】設計 §6 全域守衛：
① facade 介面（dowob.xyz.blog.infrastructure.facade..）方法：名稱以 Id／Ids 結尾的參數，型別不得為 Long／long／Collection<Long>
   （取參數名需要 class 檔保留參數名——先確認編譯有 `-parameters`〔Spring Boot parent 預設開啟〕，沒有就改用 Java reflection `Parameter#getName` 並在 pom 開啟）；
   回傳型別不得為 Map<Long, …> 或含 Long id 欄位的 DTO——判定規則以「參數名／欄位名 *Id、*Ids」為準，計數類（count、total）列白名單
② EventContractTest、CrossModuleImportTest 例外清單必須為空（刪除清單機制或保留空清單，擇一並說明）
③ IT：以 information_schema 斷言沒有跨模組 FK——模組→表的對照表在本 Task 重寫為完整版（現行 CrossModuleBoundaryTest 的
   BUSINESS_TABLE_OWNERS 只有 articles、comments 兩張表；需涵蓋所有業務表，含 user_preferences 屬 version）
④ 新增守衛：除 user 模組外，mapper SQL 不得 JOIN／FROM users（現行守衛 #5 豁免 users）
每條都要有反向驗證 fixture。
【驗收條件】守衛全綠；反向驗證各附一次紅燈輸出
<§1.2 共通回報格式>
```

### Task Z3 — 打開 MQ 重試

```
<§1.1 共通頭>
【目標與動機】設計 §6。所有 consumer 已冪等。依 findings 順序：PERF-15（手刻 container factory 使 spring.rabbitmq.listener.simple.* 失效）
→ ARCH-02＋PERF-30（retry interceptor 是死碼；修活時必須設 concurrency，避免單一訊息卡住整條 queue）。
- ConsumerExecutor 改為拋例外交由 retry interceptor 處理
- **冪等順序**：現行 IdempotencyService.markProcessed 是「先標記（REQUIRES_NEW 即時提交）再處理」——直接套重試會讓第一次失敗的訊息
  在重試時被判「已處理」而遺失。改為：**`processed_events` 的寫入與業務寫入在同一個資料庫交易中**（業務失敗 → 標記一起 rollback；
  標記與業務一起 commit → 重送必被跳過），冪等檢查集中在 ConsumerExecutor。業務不寫資料庫的 consumer（例如只寫 ES、Redis）在回報中逐一列出並說明重複處理的影響
- stateful retry 需要 AMQP messageId：發送端開啟（RabbitTemplate 的 MessagePostProcessor 或 setCreateMessageIds 等），與 eventId 對齊
【驗收條件】IT（真 RabbitMQ＋Postgres）：
- 第 1 次失敗、第 2 次成功，且業務確實執行一次（不是被冪等跳過）
- 業務寫入後、commit 前失敗 → 標記也被 rollback，重試時業務會再執行且最終只生效一次
- 同一 eventId 重送只處理一次
- 超過重試次數進 DLQ，dead_lettered 計數正確
- 一則持續失敗的訊息不阻塞同 queue 的其他訊息（concurrency）
<§1.2 共通回報格式>
```

主線在本階段另外處理：findings 中 ARCH-30、ARCH-07、ARCH-09、ARCH-13、ARCH-02、PERF-15、PERF-30 與相關 RACE 條目轉 DONE；`architecture.md` 修訂提案（移除「users 可直接 JOIN」、新增 UUID／無跨模組 FK／事件＋校正）交 Yuan 簽核。

---

## 6. 階段完成檢查表（主線）

- [ ] 再次確認**仍無正式環境**（設計 §4 規則）
- [ ] 每個 Task 的審查 agent 回報已處理（HIGH 以上修掉或說明）
- [ ] 抽查 RED log 確實紅、GREEN log 綠；自己重跑 grep 證據
- [ ] migration 與 `schema.md` 一致、在授權範圍內；pg_indexes 對照表合理
- [ ] 守衛例外清單確實縮短
- [ ] IT 實際在本機跑過（或 PR 說明寫明以 CI 為準）
- [ ] 開 PR（base `develop`）：設計章節、驗收證據、未確認事項
- [ ] Yuan 合併後依 §0.2 重建分支
- [ ] 更新 `ai-docs/backlog/2026-10-24-todo-review-decisions.md` §8 進度
