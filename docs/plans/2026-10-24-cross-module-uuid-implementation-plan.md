# Implementation Plan：跨模組識別全面改為 UUID 並解除模組耦合

## Design Reference

- Design doc: `docs/plans/2026-10-24-cross-module-uuid-design.md`
- Approved by: Yuan（2026-10-24，逐段確認）
- 執行模型：**Sonnet**（`model: "sonnet"`）。升級規則見 §1.3

---

## 0. 本檔怎麼用（主線 session 必讀）

1. 本計畫分成 **11 個階段**：0a、0b、M1～M8（每個模組一個）、Z（收尾）。**一個階段 = 一個 PR**，依序進行，前一個 PR 合併進 `develop` 後才開始下一個。
2. 每個階段內有數個 Task。每個 Task 派**一個新的 Sonnet 實作 agent**，完成後派**另一個 Sonnet 審查 agent**（§1.4），主線抽查後才進下一個 Task。
3. 0a、0b 的 Task 已寫成完整提示詞（§2、§3）。M1～M8 共用 **§4 的模組提示詞範本**，派工前主線必須先做 §4.1 的「派工前刷新」，把盤點結果填進範本——**前面的階段會改變程式碼，本檔的行號在那時可能已失效**。
4. 階段完成：主線依 §6 的檢查表抽查 → 開 PR（base：`develop`）→ Yuan 審查並合併 → 依 §6 重建分支 → 下一階段。

### 0.1 分支

- PR 的來源分支固定為 `claude/confident-cerf-324ev7`（本 session 指定分支）
- 每個 PR 合併後：`git fetch origin develop && git checkout -B claude/confident-cerf-324ev7 origin/develop`，再以 `git push --force-with-lease -u origin claude/confident-cerf-324ev7` 推送（此時分支只含已合併的歷史）
- 實作 agent 使用 `isolation: "worktree"`，在自己的 worktree 內 commit；主線把 worktree 的 commit 合回指定分支（cherry-pick 或 merge），**不得**讓 agent 直接 push

### 0.2 環境

- 雲端 container 有 Java 21、Maven、`./mvnw`；**Docker daemon 未啟動** → Testcontainers IT 可能無法在本機跑。每個階段開工前主線先試 `docker info`；不可用時 IT 以 **CI 結果**為準
- 首次跑 Maven 需下載依賴，較慢；主線在 0a 開工前先跑一次 `./mvnw -q -DskipTests install --no-transfer-progress 2>&1 | tee logs/bootstrap.log` 暖機

---

## 1. 共用規則

### 1.1 共通頭（每個實作提示詞的第一段，原文照貼）

```
Working directory: <WORKTREE_FULL_PATH>
All commands (mvnw, git, file edits) MUST be run from this directory.
Do NOT operate on the main repository root.
---
你是在 Spring Boot 模組化單體 repo（blog-web-v2）中執行一個明確範圍任務的實作者。
先讀以下檔案中與本任務相關的章節（不要整份貼回來）：
- CLAUDE.md（TDD 鐵律、Test Execution Rules）
- ai-docs/code-standards.md、ai-docs/testing-standards.md、ai-docs/security.md
- ai-docs/architecture.md（跨模組邊界）、ai-docs/flyway-convention.md（若本任務含 migration）
- docs/plans/2026-10-24-cross-module-uuid-design.md（本任務所屬的設計，讀 §0 與你被指定的章節）

硬規則：
1. TDD：先寫失敗測試並實際跑出紅燈（存 log），才寫實作；再跑出綠燈（存 log）。
2. 測試輸出一律 `2>&1 | tee logs/<task-id>-<red|green|baseline>.log`；單一失敗先讀
   `<module>/target/surefire-reports/TEST-*.xml`，只重跑受影響的測試類別，永不為單一失敗重跑全套。
3. 不弱化、不刪除、不 @Disabled 任何既有測試；需要修改既有測試時，在回報中逐一說明原因。
4. 不碰已套用的 migration 檔（V1～現有最大版號）；新增 migration 用下一個未使用的版號
   （`ls blog-db-migration/src/main/resources/db/migration | sort -V | tail -1` 確認），並同步 ai-docs/schema.md。
5. 所有新類別與公開方法要有繁體中文 JavaDoc；測試方法名稱與 @DisplayName 遵守 testing-standards.md。
6. Testcontainers IT 需要 Docker。先跑 `docker info`；不可用時照樣寫 IT 並確認可編譯，
   在回報中明確寫「IT 未在本機執行（無 Docker）」——絕不可把沒跑寫成通過。
7. 在 worktree 內以 Conventional Commits、繁體中文 subject 提交（見 ai-docs/git-convention.md），不要 push。
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
6. 未確認／風險清單
7. commit hash 清單
```

### 1.3 升級與止損

- 同一 Task，Sonnet 實作連續兩次未通過驗收 → 帶完整失敗軌跡（兩次的提示詞、回報、驗收失敗原因）改派 Opus（`model: "opus"`）
- Opus 仍不過 → 停下，整理失敗軌跡問 Yuan
- **0b 的 T0b-4（全模組 principal 替換）若 Sonnet 第一次就出現漏改**（守衛紅或 grep 有殘留），直接升 Opus，不等第二次

### 1.4 審查提示詞（每個 Task 完成後派，fresh context，Sonnet）

```
Working directory: <WORKTREE_FULL_PATH>
---
【目標與動機】從反方立場審查 Task <ID> 的變更（`git diff <base>..HEAD`），任務描述如下：
<貼上該 Task 的「目標」與「驗收條件」>
你的工作是找出它**沒做到**或**做錯**的地方，不是確認它做完了。
【檢查清單】
- 驗收條件逐條自行驗證（自己跑指令／grep，不採信實作者的回報）
- ai-docs/judgment.md §2 危險模式訊號表逐條 grep
- code-standards.md（Transaction + MQ 時序、Error Handling）、security.md（原則 1/7/8/10）
- architecture.md 跨模組邊界；本設計 §0「不變」清單是否被違反（模組自有主鍵、模組內部關聯、刪除語意、對外 API）
- 測試：RED 是否真的紅過（看 log）；是否有測試只斷言 mock 自己的回傳（套套邏輯）；是否弱化既有測試
- 自我驗證矩陣是否漏了最難的那一格（institution-notes.md 2026-09-05）
【驗收條件】每條 finding 附 file:line＋違反的規範或驗收條件＋建議修法；沒有 finding 也要列出「檢查過什麼、怎麼查的」。
【回報格式】severity 表（CRITICAL/HIGH/MEDIUM/LOW），不確定的標 PLAUSIBLE；≤ 50 行。
```

---

## 2. 階段 0a：安全網與事件契約（PR #1）

> 不改變任何模組的業務行為。完成後：所有 consumer 有計數、有校正排程框架、有兩條帶例外清單的守衛。

### Task T0a-1 — blog-infrastructure/MQ：consumer 共用包裝

- **相依**：無
- **驗證**：`./mvnw test -pl blog-infrastructure -am --no-transfer-progress`

```
<§1.1 共通頭>
【目標與動機】
10 個 @RabbitListener 各自手寫 try { ... basicAck } catch { basicNack(requeue=false) } 樣板（findings ARCH-02、roadmap C1），
且沒有任何失敗計數——訊息進 DLQ 沒有人知道。本任務只建立共用包裝，下一個 Task 才套用。
在 blog-infrastructure 新增 `ConsumerExecutor`（套件 dowob.xyz.blog.infrastructure.mq，名稱可依既有命名慣例微調）：
- 方法：`void execute(String consumerName, Channel channel, long deliveryTag, ThrowingRunnable body)`
- 行為**與現行樣板完全一致**：body 成功 → basicAck；body 拋例外 → log.error ＋ basicNack(deliveryTag, false, false)；
  nack 本身 IOException → log.error，不再往外拋
- 計數（Micrometer，比照 BatchedQuery 的做法：全域 registry 未掛子 registry 時不得報錯）：
  `mq.consumer.processed{consumer}`、`mq.consumer.failed{consumer}`、`mq.consumer.dead_lettered{consumer}`
  （requeue=false 的 nack 即視為 dead_lettered）
- **不得**打開重試、不得改成拋例外給 retry interceptor（設計 §3.1：Yuan 決定收尾階段才打開）
【範圍】只動 blog-infrastructure。
【驗收條件】
- ConsumerExecutorTest 覆蓋：成功 ack＋processed 計數；失敗 nack(requeue=false)＋failed/dead_lettered 計數；nack IOException 不外拋；
  registry 無子 registry 時不報錯
- RED log 與 GREEN log 存在
- grep 確認沒有任何 consumer 被修改（`git diff --stat` 只含 blog-infrastructure）
<§1.2 共通回報格式>
```

### Task T0a-2 — 各模組 consumer：套用共用包裝（重構）

- **相依**：T0a-1
- **驗證**：每個受影響模組 `./mvnw test -pl <module> -am --no-transfer-progress`

```
<§1.1 共通頭>
【目標與動機】把以下 9 個檔案中的 @RabbitListener 方法改用 T0a-1 的 ConsumerExecutor，消除重複樣板。這是**純重構**。
- blog-module-article/.../consumer/ViewCountConsumer.java
- blog-module-file/.../consumer/ThumbnailConsumer.java
- blog-module-recommend/.../consumer/ArticlePublishedConsumer.java
- blog-module-search/.../listener/ArticleSearchListener.java（可能含多個 listener 方法）
- blog-module-series/.../consumer/SeriesArticleDeletedConsumer.java
- blog-module-tag/.../consumer/TagUsageConsumer.java
- blog-module-user/.../consumer/EmailVerificationConsumer.java
- blog-module-user/.../consumer/PasswordResetConsumer.java
- blog-module-version/.../consumer/ArticleVersionConsumer.java
（以 `grep -rln "@RabbitListener" --include=*.java blog-*/src/main` 為準；若數量不同，回報差異）
【前置】先跑所有受影響模組的測試建立綠基線，存 logs/T0a-2-baseline.log。
【禁區】行為不得改變：ack/nack 時機、冪等檢查（IdempotencyService）的位置與語意、log 內容的關鍵資訊、MQ 訊息格式全部凍結。
不新增功能、不改 queue 設定。注意：既有的「冪等命中 → ack 並 return」分支必須保留在 body 內且仍然 ack。
【驗收條件】
- 基線綠 → 重構後同樣綠（兩份 log）
- `grep -rn "basicNack\|basicAck" --include=*.java blog-module-*/src/main` 只剩 ConsumerExecutor 以外**零命中**
  （若某 consumer 有無法收斂的特殊 ack 邏輯，保留並在回報中逐一說明理由）
- 既有 consumer 測試若直接 verify(channel).basicAck，改為透過真實 ConsumerExecutor 驗證，不得改成 mock 掉 executor 後只驗呼叫
<§1.2 共通回報格式>
```

### Task T0a-3 — blog-infrastructure/Job：孤兒校正排程框架

- **相依**：無
- **驗證**：`./mvnw test -pl blog-infrastructure -am --no-transfer-progress`

```
<§1.1 共通頭>
【目標與動機】
設計 §0 目標 2：跨模組 FK 拿掉後，「刪文章 → 其他模組資料連帶刪除」改靠事件；事件遺失時要有每日校正把孤兒資料清掉並留下計數。
本任務只建框架，各模組在各自的階段實作子類別。
新增抽象類 `OrphanReconciliationJob`（套件 dowob.xyz.blog.infrastructure.reconciliation）：
- 子類別提供：`String module()`、`String table()`、分頁取出本表中對外參照的 UUID（`List<UUID> fetchReferencedUuids(UUID afterExclusive, int limit)`，keyset 分頁）、
  `Set<UUID> filterExisting(Collection<UUID>)`（委派 owner facade）、`int deleteByReferencedUuids(Collection<UUID>)`
- 框架負責：Redis 分散式鎖（比照 blog-module-recommend/.../job/TrendingRefreshJob.java 的 setIfAbsent＋TTL 寫法，鎖 key 含 module 與 table）、
  逐頁處理、計數 `reconciliation.orphans_removed{module,table}`、`reconciliation.runs{module,table,result}`、每次執行 log 一行摘要
- 排程由子類別以 @Scheduled 觸發（建議每日一次，cron 由子類別決定）；框架提供 `run()`
- 刪除只針對「owner 端確定不存在」的 UUID；owner facade 拋例外時整次中止、不得刪任何資料（fail-safe）
【範圍】只動 blog-infrastructure。
【驗收條件】
- 測試覆蓋：取鎖失敗 → 不處理；多頁走訪；只刪不存在者；filterExisting 拋例外 → 零刪除且 result=failed 計數；計數與 log
- 以一個測試用子類別驗證（放 src/test），不得在 src/main 新增任何具體子類別
<§1.2 共通回報格式>
```

### Task T0a-4 — Facade：owner 端存在性查詢

- **相依**：無
- **驗證**：`./mvnw test -pl blog-module-article,blog-module-series -am --no-transfer-progress`

```
<§1.1 共通頭>
【目標與動機】校正排程需要問 owner「這些 UUID 哪些還存在」。新增：
- `ArticleFacade.filterExistingUuids(Collection<UUID>)`：回傳仍存在的文章 uuid（**不分狀態**——草稿也算存在）
- `SeriesFacade.filterExistingUuids(Collection<UUID>)`
介面在 blog-infrastructure/.../facade，實作在各自模組。比照既有的 `ArticleFacadeImpl.filterPublishedUuids`：
null／空輸入不發查詢、去重、經 `BatchedQuery` 切批（避免 `IN ()` 與超長 IN）。
【範圍】facade 介面 2 個方法＋article、series 各一個實作與 mapper 查詢。不改任何既有方法。
【驗收條件】
- 單元測試：空輸入不查 DB、去重、結果正確
- IT（真 Postgres）：草稿、已發布、已刪除各一筆 → 只回前兩者；超過一個批次大小的輸入正確切批
- JavaDoc 標明「不分狀態」以及與 filterPublishedUuids 的差異
<§1.2 共通回報格式>
```

### Task T0a-5 — blog-start/Architecture：事件契約與跨模組 import 守衛

- **相依**：無
- **驗證**：`./mvnw test -pl blog-start -am -Dtest='*BoundaryTest,*ContractTest' -Dsurefire.failIfNoSpecifiedTests=false --no-transfer-progress`

```
<§1.1 共通頭>
【目標與動機】設計 §3.1：兩條 ArchUnit 守衛，都以「例外清單」起步，各模組階段逐步清空，收尾時清單必須為空。
參考既有寫法：blog-start/src/test/java/dowob/xyz/blog/architecture/EndpointAuthorizationTest.java（含「例外清單不得有死條目」）、
CrossModuleBoundaryTest.java。
1. EventContractTest：所有事件類別（類名以 Event 結尾、位於 `..event..` 套件；含 blog-module-user 的 model.event）
   (a) 必須有 `java.util.UUID eventId` 欄位或 record component
   (b) 不得有 `Long`/`long` 型別的欄位或 record component
   (c) 必須位於 `dowob.xyz.blog.infrastructure.event..`
   三條各自一份例外清單（類別全名）。
2. CrossModuleImportTest：`dowob.xyz.blog.module.<A>..` 不得依賴 `dowob.xyz.blog.module.<B>..`（A≠B）。
   例外清單以「來源類別 → 目標類別」為單位（現有約 11 處，以 ArchUnit 實際掃描結果為準）。
3. 兩個測試都要有：防死條目測試（清單中每一項都必須仍是實際違規）、反向驗證（以 src/test 內的 fixture 類別證明守衛抓得到違規）。
【已知現況（以實際掃描為準）】
- 缺 eventId 的事件約 6 個（ArticleUpdatedEvent、ArticleContentChangedEvent、ImageUploadedEvent、UserRegisteredEvent、
  UserPasswordResetRequestedEvent、ArticlePublishedEvent）
- 帶 Long 的事件約 6 個；ArticleTagEvent 有兩份重複定義（findings ARCH-01）
- 跨模組 import：reading、series → article 的 ArticleQueryService／ArticleSummaryResponse；series、version → article 的事件類別與 ArticleRabbitMqConfig；
  version → ArticleMarkdownRenderer／ArticleTocCodec／RenderResult
【驗收條件】
- 兩個守衛在現況下全綠；把任一例外條目刪掉 → 對應測試紅（在回報中附這次紅燈的輸出，之後還原）
- 例外清單每一條都有一行註解說明預計在哪個階段（M1～M8／Z）清除
<§1.2 共通回報格式>
```

---

## 3. 階段 0b：登入身分（PR #2）

> 風險最高的階段：一次動到所有模組。principal 型別一換，**漏改的地方不會編譯失敗，而是靜默變成「未登入」**——
> `@AuthenticationPrincipal Long userId` 會拿到 null；`ArticleResponseMapper.java:288` 的 `getPrincipal() instanceof Long` 會變 false。
> 所以本階段的守衛（T0b-5）與 grep 驗收是必要條件，不是加分。

### Task T0b-1 — blog-common/blog-infrastructure：AuthUser 與 JWT subject

- **相依**：0a 已合併
- **驗證**：`./mvnw test -pl blog-common,blog-infrastructure -am --no-transfer-progress`

```
<§1.1 共通頭>
【目標與動機】設計 §3.2。新增 `AuthUser(Long id, UUID uuid, Role role)` record（放 blog-common，讓所有模組可用；
id 為過渡欄位，JavaDoc 標註「收尾階段移除，新程式碼一律用 uuid()」）。
JwtService：access／refresh token 的 subject 改為使用者 uuid 字串；`getUserIdFromToken` 改名為 `getUserUuidFromToken` 並回傳 UUID；
舊格式（subject 是數字）的 token 一律視為無效（Yuan 決定直接切換，無過渡期）。
SecurityUtils：新增從 Authentication 取 AuthUser 的方法；既有 isAdmin/resolveRole 行為不變。
【範圍】blog-common、blog-infrastructure 的 JwtService 與 SecurityUtils；**本 Task 不改 filter 與任何 controller**。
【驗收條件】
- JwtServiceTest：簽發的 token subject 為 uuid；數字 subject 的 token 驗證失敗；過期／簽章錯誤行為不變
- 呼叫端的編譯錯誤（例如 user 模組呼叫 generateAccessToken）在本 Task 以**最小改動**修到可編譯（傳入 uuid），行為修改留給 T0b-3
<§1.2 共通回報格式>
```

### Task T0b-2 — blog-infrastructure/Security：AuthStateLoader 與 filter（含 Q9）

- **相依**：T0b-1
- **驗證**：`./mvnw test -pl blog-infrastructure -am --no-transfer-progress`

```
<§1.1 共通頭>
【目標與動機】設計 §3.2＋Q9。現在 JwtAuthenticationFilter 自己做「讀 Redis user:auth:{id}，miss 查 DB 回填」，
而 /auth/refresh（user 模組 AuthController）只讀 Redis、miss 時誤判停權（findings SEC-26）。
抽出 `AuthStateLoader`（blog-infrastructure）：輸入 uuid，回傳 {id, uuid, status, role, tokenVersion}；
Redis key 改為 `user:auth:{uuid}`（RedisKeyConstant.getUserAuthKey 改收 UUID），hash 增加 id 欄位；
miss 時經既有的 DB 查詢介面（filter 現在用的那個）以 uuid 查詢並回填，TTL 沿用 USER_AUTH_TTL_DAYS。
filter 改用 loader，principal 改為 AuthUser（authorities 計算不變）。
【禁區】token version 比對、status 檢查、authorities 計算的語意不得改變；security.md 原則 10（afterCommit 撤銷）不得破壞。
【驗收條件】
- AuthStateLoaderTest：命中不查 DB；miss 查 DB 並回填所有欄位含 id 與 role；DB 也查不到 → 回傳 empty
- JwtAuthenticationFilterTest：principal 為 AuthUser 且 id/uuid/role 正確；既有 version 不符、非 ACTIVE 的拒絕案例仍成立
- 改寫的既有測試逐一說明
<§1.2 共通回報格式>
```

### Task T0b-3 — blog-module-user：登入、refresh、登出、撤銷改用 uuid

- **相依**：T0b-2
- **驗證**：`./mvnw test -pl blog-module-user -am --no-transfer-progress`

```
<§1.1 共通頭>
【目標與動機】設計 §3.2。
- AuthController.refresh：改用 AuthStateLoader（修掉 SEC-26：Redis miss 不再誤判 ACCOUNT_SUSPENDED）；refresh ZSet key 改用 uuid
- AuthService：login、logout、裝置數修剪、登入失敗計數的 Redis key 全部改用 uuid（RedisKeyConstant 對應方法改收 UUID）
- SessionRevoker：刪除的 key 改用 uuid；仍在 afterCommit 執行
- 搜尋紀錄 key（getSearchHistoryKey）改收 UUID，呼叫端（search 模組）同步修改
【驗收條件】
- refresh：Redis auth hash 被刪掉後 refresh 仍成功並回填（SEC-26 的 RED 測試）；status 非 ACTIVE 仍拒絕
- 改密碼、重設密碼後撤銷的 key 是 uuid 版（測試斷言 key 名稱）
- `grep -rn "getUserAuthKey\|getUserRefreshKey\|getLoginFailKey\|getSearchHistoryKey" --include=*.java blog-*/src/main` 每個呼叫都傳 UUID
<§1.2 共通回報格式>
```

### Task T0b-4 — 全模組 controller 與測試：principal 型別替換

- **相依**：T0b-3
- **驗證**：逐模組 `./mvnw test -pl <module> -am --no-transfer-progress`，最後 `./mvnw test --no-transfer-progress 2>&1 | tee logs/T0b-4-full.log`（本 Task 允許跑全套一次，因為它碰所有模組）
- **升級**：見 §1.3，第一次出現漏改即升 Opus

```
<§1.1 共通頭>
【目標與動機】設計 §3.2。principal 已改為 AuthUser。把所有 `@AuthenticationPrincipal Long xxx` 改為 `@AuthenticationPrincipal AuthUser user`
（可為 null 的公開端點維持可為 null），方法內原本用 Long 的地方一律改用 `user.id()`（或 `user == null ? null : user.id()`）。
這是**純型別替換**，所有業務邏輯照舊使用 Long id；改成 uuid 是各模組階段的工作，不是本 Task。
同時處理所有直接讀 principal 的地方：
- `grep -rn "getPrincipal()" --include=*.java blog-*/src/main`（已知：ArticleResponseMapper.java:288 的 `instanceof Long`、ArticleQueryService.java:301）
測試：各測試類別的 asUser(Long, Role) 之類 helper 改為放入 AuthUser（uuid 用固定的測試 UUID，id 維持原值）。
【範圍】約 63 處 @AuthenticationPrincipal（分布：article 2 檔、comment 2、file 1、reading 4、search 1、series 1、tag 1、user 2、version 2）
與約 31 個測試檔。逐模組處理，每個模組改完就跑該模組測試。
【禁區】不改任何業務邏輯、不改 SQL、不改 facade；不弱化任何斷言。
【驗收條件】
- `grep -rnE "@AuthenticationPrincipal\s+(final\s+)?Long" --include=*.java blog-*/src` 零命中
- `grep -rn "instanceof Long" --include=*.java blog-*/src/main` 中與 principal 相關者零命中
- 全套測試綠（logs/T0b-4-full.log）；若無 Docker，IT 部分明確標註未執行
<§1.2 共通回報格式>
```

### Task T0b-5 — blog-start/Architecture：principal 型別守衛

- **相依**：T0b-4
- **驗證**：`./mvnw test -pl blog-start -am -Dtest='PrincipalTypeTest' --no-transfer-progress`

```
<§1.1 共通頭>
【目標與動機】T0b-4 的錯誤不會造成編譯失敗，只會靜默降級為未登入，必須以測試擋住未來回歸。
新增 ArchUnit `PrincipalTypeTest`：
(a) 任何標註 @AuthenticationPrincipal 的參數型別必須是 AuthUser
(b) src/main 中不得出現對 `Authentication.getPrincipal()` 的呼叫，除了白名單（SecurityUtils、AuthStateLoader/filter 所在類別）——
    其他地方一律改用 SecurityUtils 提供的方法取得 AuthUser；若 T0b-4 留有呼叫點，本 Task 一併改掉
(c) 反向驗證 fixture：Long 型別的 @AuthenticationPrincipal 參數、白名單外呼叫 getPrincipal()，兩者都要被抓到
【驗收條件】守衛綠；反向驗證兩項皆紅（附輸出）
<§1.2 共通回報格式>
```

---

## 4. 階段 M1～M8：模組提示詞範本

### 4.1 派工前刷新（主線做，不派 agent）

每個模組階段開工前，在最新的 develop 上做：

1. `grep -rnE "\b(user_id|author_id|uploader_id|article_id|series_id)\b" blog-module-<m>/src/main` → 本模組仍用 Long 的欄位與 SQL
2. `grep -rnE "\bLong (userId|authorId|articleId|seriesId|viewerId|operatorId|currentUserId)\b" blog-module-<m>/src/main` → 參數
3. `grep -rniE "join users|from users" blog-module-<m>/src/main` → 直接 JOIN users
4. EventContractTest、CrossModuleImportTest 例外清單中屬於本模組的條目
5. 本模組呼叫的 facade 方法中，owner 尚未提供 UUID 版本者
6. findings 中本模組待併入的 RACE／T2 條目（設計 §5）
7. 下一個可用的 Flyway 版號

把結果填入 §4.2 的 `<盤點結果>`。

### 4.2 模組實作提示詞範本（一個模組可拆 2～3 個 Task：儲存層、服務與跨模組、刪除連動；同一個 agent 也可連續做，依規模決定）

```
<§1.1 共通頭>
【目標與動機】
設計 docs/plans/2026-10-24-cross-module-uuid-design.md §4（標準流程）與 §5 第 <N> 列（<模組名>）。
把 <模組名> 模組中所有指向其他模組的 Long 識別改為 UUID，並解除它對其他模組的資料庫與程式碼耦合。做完後本模組：
- 表中沒有指向其他模組的 Long 欄位、沒有跨模組 FK
- 程式中沒有其他模組的 Long id（參數、DTO 欄位、事件欄位）
- 不 JOIN 其他模組的表（含 users），不 import 其他模組的類別
- 文章／系列被刪除時，本模組相關資料由事件刪除，並有每日校正排程兜底

【盤點結果】（主線依 §4.1 填入）
<欄位清單、Long 參數清單、JOIN users 位置、例外清單條目、缺少的 UUID 版 facade、RACE 條目、下一個 Flyway 版號>

【本模組特別事項】（設計 §5 第 <N> 列原文）
<貼上>

【流程】依序：
1. 基線：`./mvnw test -pl blog-module-<m> -am --no-transfer-progress 2>&1 | tee logs/<task>-baseline.log`
2. RED：先寫失敗測試，至少涵蓋
   - repository／mapper 以 uuid 讀寫（IT）
   - 刪除連動：收到 owner 的刪除事件後，本模組相關資料被刪除；重複收到同一事件不出錯（冪等）
   - 校正排程子類別：孤兒被刪、非孤兒保留（IT）
   - 本模組的 T2 條目：同一請求並發／重複兩次 → 回 200 且計數只變一次（IT，真 Postgres，不得用 mock 模擬約束）
   - 原本 JOIN users 的查詢改為批次查詢後：一頁 N 筆只呼叫一次 UserFacade 批次方法（verify times(1)）
3. Migration（V<下一版號>__<描述>.sql）：
   新增 <x>_uuid UUID → 由 owner 表回填（UPDATE ... FROM users/articles/series）→ SET NOT NULL →
   建涵蓋所有列的索引（含刪除與校正用的 <x>_uuid 單欄索引）→ DROP 舊 Long 欄位（連同其 FK 與索引）→
   在 uuid 欄位重建原本的 UNIQUE。**新 uuid 欄位不建 FK。** 同步 ai-docs/schema.md（欄位、索引、FK、Migration Index）。
   授權範圍：設計 §4「Migration 授權」。超出範圍的 DROP（模組自有主鍵、模組內部關聯）一律禁止。
4. entity／mapper／repository 改 uuid；T2 改 `INSERT ... ON CONFLICT`，依 affected rows 決定是否更新計數
   （範本：blog-module-reading/.../mapper/ReadingProgressMapper.java、tag 模組 UserTagFollowRepository.follow）。
5. service／controller 改用 `AuthUser.uuid()`；呼叫 facade 的 UUID 版本。owner 模組缺 UUID 版方法時，可在本 Task 於 owner 模組**新增**
   （介面＋實作＋測試），**不得刪除或修改既有 Long 版方法**。
6. JOIN users → `UserFacade` 批次方法（缺就新增 UUID 版，例如 getAuthorInfoByUuids），一頁只查一次。
7. 移除本模組對其他模組的直接 import；事件類別移到 blog-infrastructure/.../event 並改為只帶 UUID＋eventId
   （發送端與所有接收端同步修改）。從 EventContractTest、CrossModuleImportTest 的例外清單刪除本模組的條目。
8. 刪除連動 consumer（用 ConsumerExecutor）＋ OrphanReconciliationJob 子類別（每日一次）。
9. 跑本模組與所有被你動到的模組測試（綠燈 log）。

【禁區】
- 對外 API 的 URL、請求、回應 JSON 格式不得改變（前端無感）
- 刪除語意不得改變：刪除前後本模組資料的結果必須與改造前相同
- 不改其他模組的業務邏輯；對 owner 模組只允許「新增 UUID 版方法」
- 不做雙寫（設計 §4：無正式環境）

【驗收條件】
- 設計 §7.1 全部項目
- grep 證據（貼在回報中）：
  `grep -rnE "\b(user_id|author_id|uploader_id|article_id|series_id)\b" blog-module-<m>/src/main` 只剩模組內部關聯
  `grep -rniE "join users|from users" blog-module-<m>/src/main` 零命中
  `grep -rn "import dowob.xyz.blog.module\." blog-module-<m>/src/main | grep -v "module.<m>\."` 零命中
- 例外清單確實縮短（附 diff）
<§1.2 共通回報格式>
```

### 4.3 各模組參數（填入範本的「本模組特別事項」）

| 階段 | 模組 | 設計 §5 列 | 補充提醒 |
|---|---|---|---|
| M1 | comment | 第 1 列 | `comments.article_uuid` 已存在且雙寫中（V24），本階段：DROP `article_id` 與 V24 的 `comments_article_uuid_fkey`、移除雙寫；`comment_likes.comment_id` 是模組內部關聯，維持 Long。留言數加減需在 ArticleFacade 新增 UUID 版 |
| M2 | reading | 第 2 列 | 閱讀進度 Redis hash 的 key 目前以 `userId:articleUuid` 組成（ReadingProgressFlushJob 以 parseLong 解析），改為 uuid 時注意舊 key 不需遷移（無正式環境）；`getSummariesByUuids` 回傳的 DTO 放 facade dto，reading 的回應 JSON 必須與現在逐欄位相同（加測試比對） |
| M3 | version | 第 3 列 | restore 改為 article 端渲染後，VersionControllerIT 既有的凍結守衛與權限案例必須仍成立 |
| M4 | series | 第 4 列 | `SeriesDeletedEvent` 由 series 發送；article 端的接收與清空 `series_uuid` 留到 M6（本階段先發事件，並確認 `articles.series_id` 的 FK `ON DELETE SET NULL` 仍在、行為不變） |
| M5 | file | 第 5 列 | DROP `files` 整張表前再 grep 一次確認零讀寫，結果附在回報 |
| M6 | article | 第 6 列 | 公開作者功能若已落地：`countPublishedByAuthor`、`getPublishedStatsByAuthor` 的內部改用 `author_uuid`，介面不變 |
| M7 | search／recommend／tag | 第 7 列 | ES mapping 變更需要重建索引；在 PR 說明中寫明部署後要執行 admin reindex |
| M8 | user | 第 8 列 | 若 Q8 尚未完成，RACE-02/03 在此處理；否則只做 UUID 化 |

---

## 5. 階段 Z：收尾（PR #11）

```
<§1.1 共通頭>
【目標與動機】設計 §6。所有模組已改造完成。
1. 移除 AuthUser.id 與所有使用點；移除 facade 所有 Long 版方法與 DTO 的 Long id 欄位（ArticleData.id、ArticleContentData.id 等）
2. 全域守衛（blog-start/.../architecture）：
   ① facade 介面（dowob.xyz.blog.infrastructure.facade..）的方法參數與回傳型別不得含代表 id 的 Long（含 Collection<Long>、Map<Long,…>）
   ② EventContractTest、CrossModuleImportTest 的例外清單必須為空（刪除清單機制本身或保留空清單，二擇一並說明）
   ③ IT：以 information_schema 斷言沒有跨模組 FK（模組→表的對照取自 CrossModuleBoundaryTest 的既有定義）
3. 打開 MQ 重試：ConsumerExecutor 改為拋例外交給 retry interceptor；以 eventId＋IdempotencyService 在包裝內集中做冪等檢查；
   依 findings 的順序處理 PERF-15 → ARCH-02＋PERF-30（設定 listener concurrency，避免單一訊息卡住整條 queue）
4. 不改 architecture.md（提案制）——改為在 ai-docs/backlog/ 寫一份修訂提案，交主線給 Yuan 簽核
【驗收條件】
- 守衛全綠、反向驗證各附一次紅燈輸出
- 重試：IT 驗證暫時性失敗會重試成功；同一 eventId 重送只處理一次；超過次數進 DLQ 且計數正確
- 全套測試綠（允許跑一次全套）
<§1.2 共通回報格式>
```

主線在本階段另外處理：findings 中 ARCH-30、ARCH-07、ARCH-09、ARCH-13、ARCH-02、PERF-15、PERF-30 與相關 RACE 條目轉 DONE；`architecture.md` 修訂提案交 Yuan 簽核。

---

## 6. 階段完成檢查表（主線）

- [ ] 每個 Task 的審查 agent 回報已處理（HIGH 以上必須修掉或說明）
- [ ] 抽查 RED log 確實有紅燈；GREEN log 綠
- [ ] 抽查 grep 證據（自己重跑，不採信回報）
- [ ] migration 與 `schema.md` 一致；授權範圍內
- [ ] 守衛例外清單確實縮短
- [ ] 本機無 Docker 時，PR 說明寫明 IT 以 CI 為準
- [ ] 開 PR（base `develop`），說明含：設計章節、驗收證據、未確認事項
- [ ] Yuan 合併後依 §0.1 重建分支
- [ ] 更新 `ai-docs/backlog/2026-10-24-todo-review-decisions.md` §8 的進度
