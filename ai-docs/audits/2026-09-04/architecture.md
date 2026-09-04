# blog-web-v2 架構審查報告（develop @ f0e3cb1）

- **審查對象**：`origin/develop`（f0e3cb1，唯讀 worktree `.worktrees/review-develop`）
- **方法**：靜態分析（rg / git / 讀檔）。未執行 Maven、未跑測試、未修改任何檔案。
- **規範真相**：`ai-docs/architecture.md`、`ai-docs/code-standards.md`、`ai-docs/judgment.md`、`ai-docs/testing-standards.md`
- **基線**：`git show docs/audit-findings-registry:ai-docs/findings.md` ＋ 同 commit 的 `ai-docs/roadmap.md`（架構層發現在 roadmap 的「體檢發現索引」，findings.md 無 ARCH 專章）
- **維度**：架構（模組邊界 / 事件契約 / 分層 / ArchUnit 守衛 / 啟動設定 / 測試架構 / 契約真相 / 技術債）
- **與安全維度的關係**：安全報告在同目錄 `be-review-security.md`。重疊處已標註「同 SEC-xx」；本報告只在「結構性解讀不同」時另立 finding。

---

## 1. 摘要

- 共 **28 條** finding：**CRITICAL 0 / HIGH 3 / MEDIUM 14 / LOW 11**。模組邊界紀律整體良好（跨模組 `import` 主線只有 12 處），PR #54 的 article↔file 循環依賴**確認已真正打斷**。
- **最該先修的三件事**：
  1. **ARCH-03（HIGH）架構守衛全線缺席**：ArchUnit 仍是零；唯一能攔「Spring 組裝期」缺陷的 `ContextSmokeTest` 被 `-Dcontext.smoke=true` gate 關閉，而 `.github/workflows/ci.yml` 兩個 job 都不設它（`-Pe2e` 還把 includes 覆寫成 `**/*E2E.java`，連帶排除它）。**那個花了 6 個任務才現形的循環依賴，今天仍然沒有任何自動防線。**
  2. **ARCH-01（HIGH）`ArticleTagEvent` 事件契約有兩份重複類別**：producer 發 `infrastructure.event.ArticleTagEvent`，consumer 收 `module.tag.event.ArticleTagEvent`，兩者無任何編譯期關聯，**JavaDoc 對同一個 `articleId` 欄位的語意已經寫成相反的兩種**（「公開 UUID」vs「資料庫主鍵」）。今天靠 Jackson 的 `INFERRED` 型別優先序僥倖能跑。
  3. **ARCH-02（HIGH）MQ 重試基礎設施是死碼**：9/9 consumer 全部手動 `try/basicAck/catch/basicNack(requeue=false)`，`RabbitMqConfig` 的 `StatefulRetryOperationsInterceptor`（3 次指數退避）永遠不會被觸發，而該類別 JavaDoc 仍宣稱「重試：最多 3 次，指數退避 1s→5s」。roadmap 工作包 C1/C2 完全未動。
- 基線對照：roadmap 的架構類發現**幾乎全部仍開放**；只有「IdempotencyService 僅 1/9 consumer」改善為 3/9、「article↔file 循環依賴」已修。backlog `2026-07-14` M1（SeriesMapper 直讀 `articles`）不但沒修，**從 4 處長到 7 處**。
- 值得記錄的正面事實：`blog-common` 是乾淨的 shared kernel；Transaction+MQ 時序（code-standards §）全站正確；`ai-docs/schema.md` 與 V1–V21 完全同步；1511 個 `@Test`、零 `@Disabled`、零 `Thread.sleep`、零中文測試方法名。

---

## 2. Findings（按 severity 排序）

### HIGH

---

#### ARCH-01（HIGH）｜`ArticleTagEvent` 事件契約重複定義：producer / consumer 各持一份，無編譯期關聯且語意已分歧

- **file:line**
  - Producer 端型別：`blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/event/ArticleTagEvent.java:23`
  - Consumer 端型別：`blog-module-tag/src/main/java/dowob/xyz/blog/module/tag/event/ArticleTagEvent.java:21`
  - 發送點：`blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleEventPublisher.java:4, 183-186`（`new ArticleTagEvent(UUID.randomUUID(), article.getUuid(), tagIds)`）
  - 接收點：`blog-module-tag/src/main/java/dowob/xyz/blog/module/tag/consumer/TagUsageConsumer.java:7, 70-72`
  - 轉換器：`blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/config/RabbitMqConfig.java:79-82`（裸 `new Jackson2JsonMessageConverter()`，未設定 type mapper）
- **違反 / 情境**：`ai-docs/architecture.md`「Modular Monolith：Facade Pattern，模組間以契約互動」；`ai-docs/code-standards.md` §Transaction+MQ「Producer/Consumer 必須配對」的精神（型別也是契約的一部分）。
  兩個 record 結構今天完全相同，但：
  - **語意文件已經打架**：infrastructure 版寫「`articleId` 文章公開 UUID」，tag 版寫「`articleId` 文章資料庫主鍵」。真相是 UUID（`article.getUuid()`）。任何讀 tag 模組那份 JavaDoc 的人都會寫錯。
  - **無編譯期關聯**：在其中一份加/改/重命名欄位，`mvn verify` 全綠，錯誤只會在 runtime 反序列化時以「欄位變 null」或整批進 DLQ 的形式出現。
  - **能跑是靠預設值**：`Jackson2JsonMessageConverter` 的 `DefaultJackson2JavaTypeMapper` 預設 `TypePrecedence.INFERRED`（listener 方法參數型別勝過 producer 寫入的 `__TypeId__` header）。一旦有人為了別的需求把 precedence 改成 `TYPE_ID` 或加上 `ClassMapper`，`ClassNotFoundException` 會讓**全部 tag usage 計數靜默進 DLQ**。（此段為 PLAUSIBLE：未實測，但預設值與失敗模式可由 Spring AMQP 契約推得。）
- **建議修法**：刪除 `blog-module-tag/.../event/ArticleTagEvent.java`，consumer 改 import infrastructure 版（tag 模組已依賴 blog-infrastructure，零成本）；順手修正 infrastructure 版 JavaDoc 明確寫「公開 UUID」。長期：所有跨模組事件 record 一律只放 `blog-infrastructure/event`（見 ARCH-07）。
- **基線對應**：**新**。（roadmap 只記了「事件類位置不一致（部分在 infrastructure、部分在 article 模組）」，未發現有一份是**重複**的。）

---

#### ARCH-02（HIGH）｜9/9 consumer 的手動 try/ack/nack 抵銷了 MQ 重試基礎設施，且 config JavaDoc 描述與實際行為相反

- **file:line**
  - 死掉的重試設定：`blog-infrastructure/.../config/RabbitMqConfig.java:141-152`（`factory.setAdviceChain(buildRetryInterceptor())`）、`:233-248`（`StatefulRetryOperationsInterceptor`，3 次、1s→25s）、`:33-38`（JavaDoc 宣稱「重試：最多 3 次，指數退避 1s→5s（乘數 5，上限 25s）」）
  - 抵銷點（9 個 consumer 全部同一樣板）：
    `blog-module-article/.../consumer/ViewCountConsumer.java:59,64,68`、
    `blog-module-tag/.../consumer/TagUsageConsumer.java:79,92,96`、
    `blog-module-search/.../listener/ArticleSearchListener.java:57,61,86,90,109,113`、
    `blog-module-series/.../consumer/SeriesArticleDeletedConsumer.java:52,57,61,66`、
    `blog-module-version/.../consumer/ArticleVersionConsumer.java:58,63`、
    `blog-module-recommend/.../consumer/ArticlePublishedConsumer.java:57,61`、
    `blog-module-file/.../consumer/ThumbnailConsumer.java:70,106,110`、
    `blog-module-user/.../consumer/EmailVerificationConsumer.java:57,61`、
    `blog-module-user/.../consumer/PasswordResetConsumer.java:55,59`
- **違反 / 情境**：`ai-docs/judgment.md §2`（「文件自稱 X 不算數」的通則）；roadmap 決策 **D2「MQ 失敗語意採 retry 接手」**。
  退化情境：ES 或 Redis 抖動 1 秒 → search index / tag 計數 / version 快照的訊息**第一次失敗就直接進 DLQ**，沒有任何重試。因為 listener 自己 catch 掉例外，advice chain 上的 retry interceptor 看不到任何例外，永遠不會啟動。設定與 JavaDoc 建立了「有 3 次重試」的錯誤心智模型，讓維運判斷失準（看到 DLQ 有訊息會以為「已經退避重試過 3 次還是不行」，實際是「試了一次」）。
- **建議修法**：執行 roadmap C1/C2——在 `blog-infrastructure` 抽一個共用 consumer wrapper（或 `@RabbitListener` 改用 AUTO ack + 讓例外往外拋），把 9 份樣板收斂為一份；在 wrapper 內做 ack/nack，讓 retry interceptor 真正接手。在那之前，至少先把 `RabbitMqConfig` 的 JavaDoc 改成描述現況，別讓文件說謊。
- **基線對應**：對應 roadmap 「**最大特徵：基礎設施已建好但沒用滿**——MQ retry interceptor 被手動 try/catch 抵銷」與工作包 **C1/C2** → **仍開放（零進度）**。

---

#### ARCH-03（HIGH）｜架構守衛全線缺席：ArchUnit 為零，且唯一的 context smoke test 在 CI 從不執行

- **file:line**
  - ArchUnit：全 repo 零命中（`rg -i "archunit|ArchTest|ArchRule"` 只命中 `ai-docs/*.md` 的待辦描述；14 個 `pom.xml` 無 `com.tngtech.archunit` 依賴）
  - Gate：`blog-start/src/test/java/dowob/xyz/blog/ContextSmokeTest.java:58`（`@EnabledIfSystemProperty(named = "context.smoke", matches = "true")`）
  - CI：`.github/workflows/ci.yml:29`（`./mvnw -B verify --fail-at-end -T 2C` — 未帶 `-Dcontext.smoke`）、`:74`（`./mvnw -B test -Pe2e -pl blog-start -am`）
  - `-Pe2e` 覆寫：`blog-start/pom.xml:170-179`（`<includes><include>**/*E2E.java</include></includes>`）→ `ContextSmokeTest`（`*Test.java`）在 e2e job 也被排除
  - 待辦：`ai-docs/backlog/2026-07-07-archunit-guards.md`（狀態 TODO）、`ai-docs/institution-notes.md:13`（「backlog 裡價值最高的一項」）
- **違反 / 情境**：`ai-docs/institution-notes.md`「文件擋不住不讀文件的人，測試才擋得住」；`ai-docs/judgment.md §2` 六條危險模式訊號**全部只有文件層防線**。
  退化情境：`ContextSmokeTest` 自己的 JavaDoc 說得很清楚——article↔file 循環依賴「躲過 6 個任務、2 輪安全複審與 400+ 綠燈單元測試」。這個測試就是為此而寫，**但它在 CI 的兩個 job 裡都不會跑**（job 1 沒設 system property；job 2 的 profile 把它排除在 includes 外）。等於今天 develop 上如果有人再引入一條建構子環，PR 一樣全綠。同理，`@Transactional` 內發 MQ、非公開端點漏 `@PreAuthorize`（BUG-2026-001 的兩個根因）也仍然只有 code review 這一道人肉防線。
- **建議修法**（最小可落地的守衛清單，皆可放 `blog-start/src/test/java/.../arch/`）：
  1. `noClasses().that().areAnnotatedWith(Transactional.class).should().callMethodWhere(target is RabbitTemplate.convertAndSend)` → `code-standards.md` §Transaction+MQ
  2. Controller 的每個 handler method 必須有 `@PreAuthorize`，白名單為 `security.md` Public Endpoints 表 → `security.md` 原則 1/7
  3. `classes().that().resideInAPackage("..module.(*)..").should().onlyDependOnClassesThat(不在 ..module.(其他)..)`，例外清單顯式列出 → `architecture.md` §Cross-Module Boundary Rules（此條會直接抓到 ARCH-04/23/26）
  4. Controller / Service 的回傳型別不得是 `..model.(*)` entity → `architecture.md`「API：Always return `ApiResponse<T>`」（會抓到 ARCH-08）
  5. `..module.(*)..mapper..` 的 `@Select/@Update` SQL 字面值不得出現他模組業務表名（`articles`/`comments`）→ 會抓到 ARCH-13
  另外：把 `-Dcontext.smoke=true` 加進 CI 的 e2e job（它已經有 Docker，邊際成本接近零），或把 ContextSmokeTest 改名為 `*E2E` 讓 `-Pe2e` 收得到。
- **基線對應**：對應 backlog `2026-07-07-archunit-guards.md` + `institution-notes.md` 第 2 點 → **仍開放**；ContextSmokeTest 的 CI 缺口為 **新**（PR #58 之後才成立）。

---

### MEDIUM

---

#### ARCH-04（MEDIUM）｜`ArticleQueryService` 成為第二個「事實 facade」：他模組跨邊界注入 article 模組的 concrete service

- **file:line**
  - 被跨模組注入：`blog-module-series/.../service/SeriesService.java:11, 44`（該行原始碼自帶註解 `// SP-X: ArticleQueryService 跨模組 inject 議題（spec §9）`——**問題已被知道但未解**）、`blog-module-reading/.../controller/BookmarkController.java:7, 33, 67`
  - 被注入者本身：`blog-module-article/.../service/ArticleQueryService.java:43-49`（`@Service`，注入 `ArticleService` + `ArticleMapper` + `ReadingFacade` + `SeriesFacade`）
- **違反 / 情境**：`ai-docs/architecture.md`「Facade Pattern：Modules interact **ONLY** via Service Interfaces」＋「業務 Data 必走 owner module 的 service **interface**」。
  三個結構性後果：
  1. 跨模組依賴的是**具體類別**而非介面，`blog-infrastructure` 的 facade 契約被繞過，`ArticleFacade` 不再是 article 模組唯一的對外面。
  2. `ArticleQueryService` 的依賴閉包含 `ReadingFacade` 與 `SeriesFacade`。因此 `reading → ArticleQueryService → ReadingFacade → ReadingFacadeImpl(reading)`、`series → ArticleQueryService → SeriesFacade → SeriesFacadeImpl(series)` 兩條路徑都**只差一個依賴就成環**。這正是 `ArticleLookupFacade` javadoc 描述的那類事故（`ArticleLookupFacade.java:41-45`「維護守則」），而且——見 ARCH-03——沒有任何自動測試會抓到。
  3. 它回傳 article 模組的 `ArticleSummaryResponse`，把 article 的 API DTO 拉進 series/reading 的契約（見 ARCH-26）。
  **本次確認目前尚未成環**：`ArticleFacadeImpl` 的閉包（`ArticleService → ArticleServiceImpl → {ArticleViewSubService, ArticleCommandSubService, ArticleQuerySubService}`）不含 `ArticleQueryService`，`SeriesFacadeImpl` 也不注入 `SeriesService`。是設計正確，不是運氣——但缺乏守衛使它是「今天正確」而非「不會退化」。
- **建議修法**：把 series/reading 需要的兩個能力（`getArticleSummariesByIds`）提到 `blog-infrastructure` 的 `ArticleFacade`（或新增 `ArticleSummaryFacade`），回傳既有的 `facade.dto.ArticleSummaryInfo` 而非 article 的 response DTO；`SeriesService:44` 與 `BookmarkController:33` 改注入該介面。同步加上 ARCH-03 的守衛 #3 固定結果。
- **基線對應**：對應 roadmap 「Medium｜reading/series/version 繞過 facade 直接 import article 內部 service」 → **仍開放**（本次補上「差一步成環」與「SP-X 註解已自承」兩項證據）。

---

#### ARCH-05（MEDIUM）｜`article.published` queue 已宣告並綁定，但沒有任何 consumer → 訊息永久堆積

- **file:line**
  - 宣告 + 綁定：`blog-module-article/.../config/ArticleRabbitMqConfig.java:35`（`QUEUE_PUBLISHED = "article.published"`）、`:96-99`（`articlePublishedQueue()`，durable）、`:106-112`（bind to `article.events` with `article.published`）
  - 全 repo 無對應 listener：`rg '@RabbitListener' --type java` 的 9 個結果中沒有任何一個 `queues` 是 `article.published`（search 用 `queue.search.index`、recommend 用 `recommend.article.published`）
  - Producer 持續在發：`blog-module-article/.../service/ArticleEventPublisher.java:128-131`
- **違反 / 情境**：`ai-docs/judgment.md §2` 第二列「新增 Consumer/binding 但 diff 裡沒有 Producer（或反之）」的鏡像違規——這裡是**有 binding、有 producer、沒有 consumer**。
  退化情境：每發布一篇文章就往這個 durable queue 塞一則永不被消費的訊息。RabbitMQ 無 TTL、無 max-length，訊息數與磁碟佔用單調成長；久了會撞 broker 的記憶體/磁碟 alarm，**進而阻塞所有 publisher**（含正常的 search/tag/version 事件）。這是慢性的，不會有任何錯誤日誌。
- **建議修法**：確認 `article.published` queue 是否為歷史遺留（search/recommend 已各自宣告自己的 queue 綁同一 routing key）。若無用途，刪除 `articlePublishedQueue()` + `articlePublishedBinding()` 兩個 bean，並在維運端手動 `queue.delete`；若保留作為稽核用途，加 `x-max-length` / `x-message-ttl` 並補一個 consumer。
- **基線對應**：**新**。

---

#### ARCH-06（MEDIUM）｜共用 exchange `article.events` 的拓撲契約有 4 份真相 + 3 份字面字串

- **file:line**
  - 4 個 bean 各自宣告同一個 exchange：
    `blog-module-article/.../config/ArticleRabbitMqConfig.java:86-89`（`articleEventsExchange()`）、
    `blog-module-search/.../config/SearchRabbitMqConfig.java:113-115`（`searchArticleEventsExchange()`）、
    `blog-module-recommend/.../config/RecommendRabbitMqConfig.java:60-62`（`recommendArticleEventsExchange()`）、
    `blog-module-tag/.../config/TagRabbitMqConfig.java:45-47`（`tagArticleEventsExchange()`）
  - 3 份字面字串常數：`SearchRabbitMqConfig.java:35`、`RecommendRabbitMqConfig.java:31`、`TagRabbitMqConfig.java:28`（皆 `= "article.events"`）
  - 另 2 個模組用相反做法（import 生產者 config + `@Qualifier`）：`blog-module-series/.../config/SeriesRabbitMqConfig.java:3`、`blog-module-version/.../config/VersionRabbitMqConfig.java:3, 31-34`
- **違反 / 情境**：`ai-docs/code-standards.md`「Consistency：Follow existing local style」——同一問題存在兩套互相矛盾的做法，新模組沒有可依循的答案。
  退化情境：`new TopicExchange(name)` 目前 4 處參數一致（durable=true / autoDelete=false）所以能跑。任何一處改成 `DirectExchange` 或 `durable=false`，`RabbitAdmin` 在 `ContextRefreshedEvent` 自動宣告時會拿到 broker 的 `PRECONDITION_FAILED (406)`，**該次 admin 宣告整批中止**，同一連線上其它 queue/binding 也一起沒宣告成功——症狀是「某些模組的訊息路由不到」而非明確錯誤。routing key 常數同樣重複（`article.published` 在 3 個檔案各定義一次）。
- **建議修法**：把 `EXCHANGE` / 全部 `ROUTING_KEY_*` 以及 exchange bean 移到 `blog-infrastructure`（例如 `ArticleEventsTopology`），所有模組一律 `@Qualifier` 引用同一個 bean，比照 series/version 的做法。
- **基線對應**：**新**。

---

#### ARCH-07（MEDIUM）｜事件 payload 規格三重不一致：`eventId` 覆蓋 4/10、ID 型別混用 Long PK 與 UUID、事件類位置分裂

- **file:line**（10 個事件 record 全清單）
  - 有 `eventId`（4）：`infrastructure/event/ArticleTagEvent.java:23`、`module/tag/event/ArticleTagEvent.java:21`、`module/article/event/ArticleDeletedEvent.java:26`、`module/article/event/ArticleViewedEvent.java:17`
  - 無 `eventId`（6）：`infrastructure/event/ArticlePublishedEvent.java:30-32`、`module/article/event/ArticleContentChangedEvent.java:21-26`、`module/article/event/ArticleUpdatedEvent.java:32-33`、`module/file/event/ImageUploadedEvent.java:18`、`module/user/model/event/UserRegisteredEvent.java:16`、`module/user/model/event/UserPasswordResetRequestedEvent.java:14`
  - 帶內部 Long PK 上線：`ArticlePublishedEvent(authorId)`、`ArticleContentChangedEvent(articleId, authorId)`、`ArticleDeletedEvent(articleId, authorId, seriesId)`、兩個 User 事件（`userId`）
  - 位置：3 在 `blog-infrastructure/event/`、6 在 `blog-module-*/event/`、1 在 `blog-module-user/model/event/`（連套件慣例都第三種）
  - 消費端後果：`blog-module-version/.../consumer/ArticleVersionConsumer.java:41-57` 無冪等（因為事件無 `eventId`）；`blog-module-recommend/.../consumer/ArticlePublishedConsumer.java:47-57` 同
- **違反 / 情境**：`ai-docs/architecture.md`「All external IDs must be UUIDs」的精神（MQ 事件是跨模組對外契約，等同 external）；roadmap 工作包 **C3「所有事件補 eventId」**、**C4「計數型 consumer 接上 IdempotencyService」**。
  退化情境：(a) 無 `eventId` 的事件在 redelivery 時無法去重 → version 快照重複寫、recommend 重複計分；(b) Long PK 上線讓 version/series/search 模組直接依賴 article 的主鍵空間，任何主鍵策略調整都是跨模組破壞性變更；(c) 三種位置慣例讓 ARCH-01 那種「重複類別」有滋生土壤。
- **建議修法**：訂一條事件契約規範寫進 `architecture.md`：所有跨模組事件 record 一律放 `blog-infrastructure/event`、一律第一個欄位是 `UUID eventId`、一律只帶 UUID 不帶 Long PK（consumer 需要 PK 就自己用 facade 查）。先補 `ArticleContentChangedEvent` 的 `eventId` 並讓 `ArticleVersionConsumer` 接上 `IdempotencyService`（roadmap C4 明列的三個計數型 consumer 中唯一未完成的一個）。
- **基線對應**：對應 findings.md **DATA-11**（「計數型 consumer 缺冪等（僅 series 有）；ArticleTagEvent 無 eventId」）→ **部分修**：`ArticleTagEvent` 已補 `eventId`，`IdempotencyService` 使用者由 1 增為 3（series / tag / article view count，證據：`SeriesArticleDeletedConsumer.java:56`、`TagUsageConsumer.java:77`、`ViewCountConsumer.java:57`），但 version snapshot 與其餘 6 個事件仍無。roadmap C3 → **仍開放（4/10）**、C4 → **部分完成（3/9 consumer）**。

---

#### ARCH-08（MEDIUM）｜Controller 直接回傳 persistence entity，並用 `@JsonIgnore` 貼在 entity 上當補丁

- **file:line**
  - `blog-module-file/.../controller/FileController.java:122`（`ApiResponse<FileMetadata>`）、`:228`（`ApiResponse<List<FileMetadata>>`）
  - `blog-module-tag/.../controller/AdminTagController.java:47`（`ApiResponse<Tag>`）
  - `blog-module-tag/.../controller/TagController.java:71`、`:92`（`ApiResponse<List<Tag>>`）
  - 補丁：`blog-module-file/.../model/FileMetadata.java:41`（`@JsonIgnore` on `newEntity`）、`:59-61`（`@JsonIgnore` on `storagePath`）
  - 洩漏的持久化內部欄位：`blog-module-tag/.../model/Tag.java:42-43`（`@Transient private boolean isNew`；`@Data` 產生 `isNew()`，Jackson 推導出屬性名 **`new`**，且**沒有** `@JsonIgnore`）→ `GET /api/v1/tags`、`/api/v1/tags/hot`、`PUT /api/v1/admin/tags/{id}` 的回應 body 都帶一個 `"new": false`
  - 對照（正確做法）：`blog-module-version/.../model/dto/response/VersionSummaryResponse.java:14`（JavaDoc 明寫「本 DTO 不含任何內部 Long ID」）
- **違反 / 情境**：`ai-docs/architecture.md`「API：Always return `ApiResponse<T>`；All external IDs must be UUIDs」與 DDD-Lite 分層（entity 是持久化模型，不是傳輸模型）。
  結構性解讀（與安全維度不同）：安全報告把 `storagePath` 洩漏判為「已修」（`@JsonIgnore` 補上了），但**從架構角度這個修法本身是問題**——它把「序列化關注點」焊進了 entity，代表 entity 已被正式當成 API DTO 使用。後果是每新增一個 entity 欄位都變成一次**未經審查的 API 契約變更**，而且防線是「記得加 `@JsonIgnore`」這種人為紀律。`Tag.isNew` 就是漏掉的那一個：一個純 Spring Data JDBC 的 `Persistable` 實作細節，現在是公開 API 的欄位。
- **建議修法**：為 file / tag 補 response DTO（`FileMetadataResponse` / `TagResponse`），Controller 回傳 DTO；`FileMetadata` 上的兩個 `@JsonIgnore` 隨之可移除（entity 回歸純持久化）。用 ARCH-03 守衛 #4 固定。
- **基線對應**：與 findings.md **AUTH-07 / FILE-01**（storagePath 洩漏）**同源但不同定性**——安全維度已判「已修」（同 `be-review-security.md` 的基線對照），本條是「修法留下的分層債 + 同一個坑漏掉的 `Tag.isNew`」，為 **新**。同時對應 roadmap 小勝利池「`FileController` 回傳 DTO（現洩漏 `FileMetadata.storagePath`）」→ **半修**（欄位遮蔽了，DTO 沒做）。

---

#### ARCH-09（MEDIUM）｜Controller 層做跨模組編排與分頁計算

- **file:line**：`blog-module-reading/.../controller/BookmarkController.java:31-33`（同時注入 `BookmarkService` + `ArticleFacade` + `ArticleQueryService`）、`:40, 50`（`articleFacade.findIdByUuid(...)` 取內部 Long PK 後餵給 service）、`:63-68`（`offset` 計算、跨兩個模組拼裝、`PageResult.of`）
- **違反 / 情境**：`ai-docs/architecture.md` DDD-Lite 分層（Controller 只做 HTTP 邊界轉換）＋「跨模組必走 service」。
  退化情境：(a) 分頁 offset 邏輯在 Controller，其他列表端點的分頁行為無法共用/一致；(b) `findIdByUuid` 回傳的**內部 Long 主鍵在 web 層流動**——只是碰巧沒被序列化出去；(c) 這段編排沒有交易邊界也沒有服務層測試點，只能靠 IT/E2E 覆蓋；(d) `findIdByUuid` 回 null 時直接 NPE→500（即基線 AUTH-08，本次確認仍在：`:40-41`、`:50-51` 無 null 檢查）。
- **建議修法**：把三行編排下沉為 `BookmarkService.getMyBookmarks(userId, page, size)`，由 service 呼叫 facade；Controller 只留 `@AuthenticationPrincipal` → service → `ApiResponse`。
- **基線對應**：對應 findings.md **AUTH-08**（null 檢查缺失，**仍開放**）與 roadmap 「reading/series/version 繞過 facade」；「Controller 做編排」本身為 **新**。

---

#### ARCH-10（MEDIUM）｜名為 `CrossModule*IT` 的整合測試把所有跨模組 facade 都 mock 掉了

- **file:line**
  - `blog-module-version/.../integration/CrossModuleVersionIT.java:117-126`（`@MockitoBean` × 9：`ConnectionFactory`、`RabbitTemplate`、`TagFacade`、`UserFacade`、`UserAuthService`、`ReadingFacade`、`SeriesFacade`、`FileFacade`、`ArticleEventPublisher`）
  - `blog-module-series/.../integration/CrossModuleSeriesIT.java:107-114`（同樣 7 個）
  - `blog-module-comment/.../integration/CrossModuleCommentIT.java:100-107`
  - `blog-module-reading/.../integration/CrossModuleReadingIT.java:105-111`
- **違反 / 情境**：`ai-docs/testing-standards.md`「**No "Mock Mode"**: Use REAL libraries and REAL patterns」（在 `code-standards.md` 亦同）＋「Unit, Integration, E2E 三層皆為必要」。
  退化情境：這四個測試類的名稱承諾「跨模組」，實際上**跨模組的接縫正好是被 mock 掉的那一層**。它們是「用真 Postgres 跑單模組」，價值是 SQL/約束驗證（那部分確實有價值），但無法攔截 facade 契約不匹配、Bean 組裝、事件端到端這三類跨模組缺陷——而這正是本 repo 已經踩過的坑。真正覆蓋跨模組的只剩 79 個 E2E（`blog-start/src/test/java/dowob/xyz/blog/e2e/`），而它們在 CI 是**單一 job、失敗即全紅**的粗粒度防線。
- **建議修法**：不必重寫——把類名改成反映事實（`SeriesPersistenceIT` 等），另外把「跨模組」的斷言責任明確歸給 E2E 並在 `testing-standards.md` 寫下這條分工；或為關鍵接縫（version restore → article status → search reindex）補一個真正不 mock facade 的 IT。
- **基線對應**：findings.md **TEST-08**（「CrossModuleVersionIT mock 掉 SeriesFacade+MQ」）→ **仍開放**，且本次證實**不只 version，四個 CrossModule IT 全部如此**，範圍應擴大。

---

#### ARCH-11（MEDIUM）｜`GlobalExceptionHandler` 無 `DataIntegrityViolation` / `OptimisticLockingFailure` 兜底 → 基線整類 500 缺單點修法

- **file:line**：`blog-common/.../exception/GlobalExceptionHandler.java:33`（唯一的 `@RestControllerAdvice`，全 repo 僅此一個）、`:48-232`（12 個具名 handler，**無** DIVE / OptimisticLocking）、`:243-248`（catch-all → HTTP 500 `"系統內部錯誤"`）
- **違反 / 情境**：`ai-docs/code-standards.md` §Error Handling「Status code mapping」隱含的完整性要求；基線交叉主題 **T2**。
  退化情境：findings.md 的 RACE-01/03/04/08/15、RACE-06 全部以「使用者操作回 500」收場（雙擊按讚、雙擊收藏、註冊撞 UNIQUE、發文時 tag 撞 UNIQUE、series slug 撞 UNIQUE、文章併發編輯）。這些在 service 層各自修需要動 6 個地方；而 `GlobalExceptionHandler` 是**唯一**的全域出口，補兩個 handler（DIVE → 409/400 + 業務錯誤碼；OptimisticLockingFailureException → 409）就能把整類「500」降級為可被前端處理的錯誤，是本次成本/效益比最高的單點。
- **建議修法**：先補這兩個 handler（便宜、低風險、不動業務碼），再依 roadmap 節奏做 T2 的 `INSERT ... ON CONFLICT` 收斂。注意 `@ExceptionHandler(Exception.class)` 目前會吃掉它們，新 handler 更具體會優先匹配，順序無虞。
- **基線對應**：對應 findings.md 交叉主題 **T2** 與「建議修復批次 1.」中的「GlobalExceptionHandler 補 DIVE/OptimisticLock 兜底」 → **仍開放（零進度）**。

---

#### ARCH-12（MEDIUM）｜surefire `<includes>` 複製在 10 個 module pom，root 無 pluginManagement；4 個模組沒有 → 新增 IT 會靜默不執行

- **file:line**
  - Root 無 surefire 設定：`pom.xml`（`<build>` 只有 compiler 與 jacoco；`grep -n "surefire" pom.xml` 無命中）
  - 10 份複製：`blog-module-article/pom.xml:18-23`、`blog-module-comment/pom.xml:18-23`、`blog-module-file/pom.xml:74-79`、`blog-module-reading/pom.xml:18-23`、`blog-module-recommend/pom.xml:18-23`、`blog-module-search/pom.xml:37-42`、`blog-module-series/pom.xml:18-23`、`blog-module-tag/pom.xml:63-68`、`blog-module-version/pom.xml:18-23`、`blog-start/pom.xml:133-138`
  - **沒有**這段設定的 4 個模組：`blog-common/pom.xml`、`blog-infrastructure/pom.xml`、`blog-module-user/pom.xml`、`blog-db-migration/pom.xml`
- **違反 / 情境**：`ai-docs/testing-standards.md`「No Exceptions：Unit, Integration, E2E tests are required」——如果測試不會被執行，寫了等於沒寫。
  退化情境：surefire 的**預設 includes 不含 `**/*IT.java`**（預設為 `Test*.java` / `*Test.java` / `*Tests.java` / `*TestCase.java`）。今天這 4 個模組剛好沒有 `*IT.java` 檔，所以沒有測試被吞掉——但只要有人在 `blog-infrastructure`（`SecurityConfig` / `JwtAuthenticationFilter` / `JwtService` 所在）或 `blog-module-user`（認證核心）新增一個 `XxxIT.java`，它會**永遠綠、永遠不跑**，而且沒有任何訊號。這兩個正是最需要整合測試的模組（見 ARCH-17 的覆蓋盤點：user 模組 0 個 IT、infrastructure 32 個 main class 只有 7 個測試類）。
  額外：`blog-module-user` 的整合測試命名為 `AuthServiceIntegrationTest`（碰巧結尾是 `Test.java` 才被收），與全站 `*IT` 慣例不一致。
- **建議修法**：把 `<includes>` 移到 root `pom.xml` 的 `<pluginManagement>`，刪掉 10 份複製；`AuthServiceIntegrationTest` 改名 `AuthServiceIT` 對齊慣例。
- **基線對應**：**新**。

---

#### ARCH-13（MEDIUM）｜`SeriesMapper` 直接讀 `articles` 業務表，從基線的 4 處增為 7 處

- **file:line**：`blog-module-series/.../mapper/SeriesMapper.java:34-35`、`:39-40`、`:48-49`、`:77-78`、`:93-94`、`:106`、`:115-117`（全部 `FROM articles` / `JOIN articles`；`:106` 另把 `status = 'PUBLISHED'` 寫成字面字串）
- **違反 / 情境**：`ai-docs/architecture.md` §邊界判斷速查表——`articles` 明列為**業務 Data**，跨模組讀寫皆須走 owner module 的 service；只有 reference data（`users` / `tags`）可直接 JOIN。
  退化情境：唯讀所以無資料完整性風險，但 (a) article 模組任何 schema 變更（欄位改名、status 語意調整）會靜默打壞 series 的查詢；(b) `'PUBLISHED'` 字面字串繞過 `ArticleStatus` enum，enum 若調整不會有編譯錯誤；(c) 諷刺點（基線已指出、本次確認仍然成立）：**同模組的寫路徑是正確的**——`SeriesService` 走 `articleFacade.updateSeriesAssignment`，只有讀路徑破例。
- **建議修法**：nav / count / enrich 三類查詢改走 `ArticleFacade`（`findPrevNav` / `findNextNav` / `countPublishedInSeries` 需要新增 facade 方法）。用 ARCH-03 守衛 #5 固定。
- **基線對應**：對應 backlog `ai-docs/backlog/2026-07-14-full-review-findings.md` **M1** → **仍開放且惡化**（基線記 4 處：`:63/:79/:92/:101`，develop 上為 7 處）。

---

#### ARCH-14（MEDIUM）｜API 契約真相斷鏈：47% 端點無 `@Operation`、全 repo 零 `@ApiResponse`、`docs/api-contract/` 停在 2026-05 且稽核不在 CI

- **file:line / 量測**
  - 端點總數 88（`rg -c '@(Get|Post|Put|Patch|Delete)Mapping'` 於 21 個 Controller），有 `@Operation` 者 47 → **41 個端點（47%）無 OpenAPI 描述**
  - 分裂是整模組的，不是零星漏：**零註解**的 5 個模組——`blog-module-article`（19 個端點：ArticleController 13 / AdminCategoryController 3 / CategoryController 2 / AdminArticleController 1）、`blog-module-file`（6）、`blog-module-search`（6）、`blog-module-tag`（8）、`blog-module-recommend`（2）；**全註解**的 9 個 Controller 屬 comment / reading / series / version / user
  - `@ApiResponse` / `@ApiResponses`：`rg '@ApiResponse' --type java -g '!**/test/**'` **零命中** → OpenAPI 完全沒有錯誤回應定義，前端只能從 `ai-docs` 或試錯得知 4xx 契約
  - 契約文件過期：`git log -1 -- docs/api-contract/backend-endpoints.md` → **2026-05-09**（`0eade7b`）；`gap-report.md` → **2026-05-15**（`d152101`）。文件自述「81 OpenAPI operations」，今天程式碼有 88 個 mapping
  - 稽核工具無法執行：`docs/api-contract/scripts/` 有 5 個 `*.test.js`（含 `ci-workflow.test.js`）與 `run-audit.sh/ps1`，但 repo 內**沒有任何 `package.json`**（`git ls-files "*package.json"` 零命中），`.github/workflows/ci.yml` 也沒有對應步驟
- **違反 / 情境**：`ai-docs/institution-notes.md` 第 3 點「前後端契約沒有單一真相……跨 repo 契約變更目前靠人記得」。
  退化情境：契約真相有三份（後端 `/v3/api-docs` runtime、`docs/api-contract/*.md` 快照、前端 `api-reference/openapi.json`），三份都沒有自動同步或校驗。`gap-report.md` 目前寫著「No unresolved current required fixes remain」——那是 3 個半月前的結論，期間至少多了 7 個端點（含 PR #56 的 withdraw、PR #54 的檔案存取）。「文件說沒有 gap」現在是一個**主動誤導**的狀態。
- **建議修法**：短期——在 `gap-report.md` 頂端標註「最後驗證日期」並承認過期，成本近零、立刻止血。中期——`ci.yml` 加一個步驟：啟動後端（e2e job 已有容器）抓 `/v3/api-docs`，與 checked-in 快照 diff，不一致就紅。`@ApiResponse` 可先只補寫入類端點的 400/403/404。
- **基線對應**：對應 roadmap 「Medium｜前端無 ESLint；**自建 OpenAPI 稽核未進 CI**」與小勝利池「文件斷鏈補齊」→ **仍開放**；「41/88 無 `@Operation`、零 `@ApiResponse`、快照過期 3.5 個月」為 **新的量化證據**。

---

#### ARCH-15（MEDIUM）｜錯誤碼放置有兩套互斥慣例，且三個模組借用他模組的錯誤碼命名空間

- **file:line**
  - 慣例 A（舊，放 shared kernel）：`blog-common/.../api/errorcode/{Article,Common,File,Tag,User}ErrorCode.java`
  - 慣例 B（新，放模組內）：`blog-module-comment/.../exception/CommentErrorCode.java`、`blog-module-reading/.../exception/ReadingErrorCode.java`、`blog-module-series/.../exception/SeriesErrorCode.java`、`blog-module-version/.../exception/VersionErrorCode.java`
  - 跨模組借用：`blog-module-search/.../controller/SearchController.java:97, 116`（`UserErrorCode.USER_NOT_FOUND`）、`blog-module-tag/.../controller/TagController.java:132, 153`、`blog-module-file/.../controller/FileController.java:262, 265, 283`
- **違反 / 情境**：`ai-docs/code-standards.md`「Consistency」＋ modular monolith 的「模組擁有自己的契約」原則。
  退化情境：(a) 新模組沒有可依循的答案（兩種寫法各 4-5 個先例）；(b) `blog-common` 這個 shared kernel 被塞進 5 個模組專屬的枚舉，任何模組要加錯誤碼都得改 common → 所有模組重編譯，也讓 common 逐步變成「什麼都往裡放」的垃圾場；(c) 借用 `UserErrorCode` 使錯誤碼**不再能從 code 反推來源模組**，前端的錯誤處理與後端的模組邊界脫鉤。
- **建議修法**：選定慣例 B（模組內）寫進 `architecture.md`；`blog-common` 只保留 `IErrorCode` + `CommonErrorCode`，其餘 4 個逐步搬回各模組（錯誤碼字串不變，純套件搬移，對前端零影響）；借用處改用自己模組的碼。
- **基線對應**：**新**。

---

#### ARCH-16（MEDIUM）｜可觀測性空白：無 metrics registry，而全鏈路 MQ 是 best-effort + log-only

- **file:line**
  - `blog-start/src/main/resources/application.yaml:91-95`（`management.endpoints.web.exposure.include: health,info` — 無 `metrics` / `prometheus`）
  - 無 micrometer registry 依賴（`grep -rn "micrometer\|opentelemetry\|tracing" --include=pom.xml` 零命中；`blog-start/pom.xml:29-32` 只有 actuator）
  - 靜默點：`blog-module-article/.../service/ArticleEventPublisher.java:66-68, 100-102, 132-134, 166-168, 187-189, 207-209`（6 個 `catch → log.warn` best-effort 發送）、`blog-infrastructure/.../config/RabbitMqConfig.java:203-216`（confirm/return callback 也只 log）
- **違反 / 情境**：這是「best-effort 架構」的必要配套缺失。系統刻意選擇了「MQ 發送失敗不影響主流程」（`code-standards.md` §Transaction+MQ 明訂），這是正確的可用性取捨——但代價是**一致性漂移只會出現在 log 裡**，沒有任何計數器、沒有告警、DLQ 深度不可見。
  退化情境：ES 幽靈文件（DATA-04）、tag 計數膨脹（DATA-01）、series count 漂移（DATA-10）這些基線已知問題，在生產環境**沒有任何方式能被主動發現**，只能等使用者回報。
- **建議修法**：加 `micrometer-registry-prometheus`、`exposure.include` 加上 `prometheus`；對 6 個 `catch` 點加 `Counter`（`mq.publish.failed{event=...}`），對 9 個 consumer 的 nack 路徑加 `Counter`。這是純加法、不動業務邏輯。
- **基線對應**：對應 roadmap 「Medium｜可觀測性空白：無 micrometer/tracing，事件發送失敗靜默（漂移不可見）」→ **仍開放（零進度）**。

---

#### ARCH-17（MEDIUM）｜`architecture.md` 只有 51 行、只列 4/13 模組、且沒有模組→模組允許矩陣

- **file:line**：`ai-docs/architecture.md`（全檔 51 行）；`:5`（「Distinct modules (`user`, `article`, `file`, `search`)」——實際 13 個模組：`pom.xml:19-34`）；`:20-51`（唯一的邊界規範是 reference/business data 二分表 + 三個 comment 模組的例子）
- **違反 / 情境**：本次審查的簡報要求「對照 architecture.md 的允許矩陣」——**該矩陣不存在**。
  退化情境：現行文件能回答「comment 能不能 JOIN users」，但回答不了本報告中每一個實際發生的邊界問題：version 能不能 import article 的 renderer（ARCH-23）？series 的 response DTO 能不能內嵌 article 的 response DTO（ARCH-26）？跨模組注入 concrete `@Service` 算不算違規（ARCH-04）？事件 record 該放哪（ARCH-07）？錯誤碼該放哪（ARCH-15）？**沒有規範真相，就沒有 ArchUnit 能寫的規則，也沒有 reviewer 能引用的條文**——這是 ARCH-03 的前置依賴。
- **建議修法**：補三張表（不需要長）：(1) 13 模組 × 13 模組的允許依賴矩陣＋每個例外的理由；(2) 每種跨模組載體（facade 介面 / facade DTO / 事件 record / 錯誤碼 / 例外）的法定放置位置；(3) 「什麼算 owner module 的對外面」的定義（只有 `blog-infrastructure/facade/*` 介面算，`@Service` 具體類不算）。寫完之後 ARCH-03 的守衛才有可對照的條文。
- **基線對應**：對應 roadmap 小勝利池「architecture.md 僅列 4/10 模組」→ **仍開放且分母變大（4/13）**；「無允許矩陣」為 **新**。

---

### LOW

---

#### ARCH-18（LOW）｜`version.snapshot` queue 獨有 `x-message-ttl`，與其他 7 個 queue 的 DLQ 慣例不一致

- **file:line**：`blog-module-version/.../config/VersionRabbitMqConfig.java:41-49`（JavaDoc 宣稱「**對齊全站 DLQ 慣例**」，但 args 多了 `"x-message-ttl", 600_000`）；對照無 TTL 的 7 個：`ArticleRabbitMqConfig.java:75-79`、`SearchRabbitMqConfig.java:69-70`、`TagRabbitMqConfig.java:58-60`、`SeriesRabbitMqConfig.java:42-43`、`RecommendRabbitMqConfig.java:50-51`、`FileRabbitMqConfig.java:59-60`、`UserRabbitMqConfig.java:56-57`
- **違反 / 情境**：`ai-docs/judgment.md §2`（JavaDoc 自稱與實際不符）。退化情境：consumer 停機或積壓超過 **10 分鐘**，版本快照事件會被 broker 判定過期並丟進 DLQ——使用者編輯的自動快照無聲消失，且因為走的是 DLQ 路徑，日誌上看起來跟「處理失敗」一樣。部署滾動更新時很容易觸發。
- **建議修法**：若 TTL 是刻意的（避免舊快照事件），把理由寫進 JavaDoc 並移除「對齊全站慣例」的錯誤說法；若不是，刪掉這一行。
- **基線對應**：**新**。

---

#### ARCH-19（LOW）｜DLQ 拓撲常數複製在 8 個模組，而 infrastructure 的正本是 `private`

- **file:line**：正本 `blog-infrastructure/.../config/RabbitMqConfig.java:50-56`（`DLQ_EXCHANGE` / `DLQ_QUEUE` / `DLQ_ROUTING_KEY` 皆 **`private static final`**）；8 份字面值複製：`ArticleRabbitMqConfig.java:75-79`、`SearchRabbitMqConfig.java:69-70`、`RecommendRabbitMqConfig.java:50-51`、`TagRabbitMqConfig.java:59-60`、`SeriesRabbitMqConfig.java:42-43`、`VersionRabbitMqConfig.java:46-47`、`FileRabbitMqConfig.java:59-60`、`UserRabbitMqConfig.java:56-57`（其中 6 個還各自寫了一份同名 `private Map<String,Object> dlqArgs()`）
- **違反 / 情境**：`ai-docs/code-standards.md`「Simplicity / Consistency」。退化情境：改 DLQ 名稱要動 9 個檔；漏改一個，該 queue 的失敗訊息會被路由到不存在的 exchange 而**靜默丟棄**（RabbitMQ 對無法路由的 dead-letter 是直接丟棄，不報錯）。
- **建議修法**：把三個常數改 `public`，在 `blog-infrastructure` 提供 `public static Map<String,Object> dlqArgs()`，8 個模組改呼叫它。
- **基線對應**：**新**。

---

#### ARCH-20（LOW）｜`autoAckContainerFactory` 是零使用者的 bean，卻有單元測試斷言它的設定

- **file:line**：`blog-infrastructure/.../config/RabbitMqConfig.java:166-179`（bean 定義）；唯一引用者是它自己的測試 `blog-infrastructure/src/test/java/.../config/RabbitMqConfigTest.java:154-161`；9 個 `@RabbitListener` 沒有任何一個指定 `containerFactory = "autoAckContainerFactory"`（`ArticleSearchListener.java:49,78,102` 顯式指定的是 `rabbitListenerContainerFactory`）
- **違反 / 情境**：`ai-docs/judgment.md §7`（抽查法：「確認測試真的驗行為，而非煙霧」）。退化情境：這是**假覆蓋**——測試綠燈給人「AUTO ack 路徑有測」的印象，實際上該路徑在生產從未被走過。bean 本身的 JavaDoc 還警告「一旦拋出異常，預設行為是無限 Requeue」，是一顆有陷阱又沒人用的地雷。
- **建議修法**：刪 bean + 刪對應測試；真的需要時再加回來。
- **基線對應**：**新**。

---

#### ARCH-21（LOW）｜`application-e2e.yaml` 開啟 `allow-bean-definition-overriding`，使唯一的 context 守衛比 production 寬鬆

- **file:line**：`blog-start/src/test/resources/application-e2e.yaml:5-6`（`spring.main.allow-bean-definition-overriding: true`）；受影響者 `blog-start/src/test/java/dowob/xyz/blog/ContextSmokeTest.java:60`（`extends AbstractE2ETest`，吃同一份 yaml）；production 側 `blog-start/src/main/resources/application.yaml` 無此設定（即 Spring Boot 預設 `false`）
- **違反 / 情境**：測試環境不得比生產寬鬆（否則守衛的語意被削弱）。退化情境：`ContextSmokeTest` 存在的唯一目的是「證明 context 組得起來」，但**重複 bean 定義**（modular monolith 中很常見的失誤，例如兩個模組各自宣告同名 `@Bean`）在 e2e profile 下會被靜默接受、在生產啟動時炸。守衛的覆蓋面因此有一個洞，且洞的形狀正好是這個架構最容易犯的錯。
- **建議修法**：`ContextSmokeTest` 用自己的 profile（或 `@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=false")`）關掉覆寫；若某個既有測試依賴覆寫，把那個依賴挖出來修掉。
- **基線對應**：**新**。

---

#### ARCH-22（LOW）｜五個 god class（>400 行 / ≥8 個注入依賴）

- **file:line**（行數 / `private final` 依賴數）
  - `blog-module-file/.../service/FileServiceImpl.java` — **605 行 / 8 依賴**（上傳、Tika 偵測、MinIO IO、配額、縮圖尺寸、授權判斷、URL 生成全在一起）
  - `blog-module-article/.../service/ArticleCommandSubService.java` — **570 行 / 12 依賴**（全 repo 最高）
  - `blog-module-user/.../service/AuthService.java` — **569 行 / 8 依賴**
  - `blog-module-article/.../facade/ArticleFacadeImpl.java` — **480 行 / 9 依賴**
  - `blog-module-version/.../service/VersioningService.java` — **456 行 / 8 依賴**
- **違反 / 情境**：`ai-docs/code-standards.md`「Simplicity: Simple > Clever」。退化情境：`ArticleFacadeImpl` 的 9 個依賴正是它成為「胖 Bean」、引發 article↔file 循環依賴的原因（`ArticleLookupFacade.java:30-34` 明白寫了這件事）。依賴數越高，越容易在不知情下把新的環引進來；而 ARCH-03 顯示沒有守衛。
- **建議修法**：不建議現在重寫（`code-standards.md`「Ask before rewriting systems」）。低風險的第一步：把 `FileServiceImpl` 的縮圖/尺寸偵測與 MinIO IO 拆成兩個 collaborator；把 `ArticleFacadeImpl` 的寫入類方法（它注入 `ArticleFileBinder` + `ArticleEventPublisher` + `TransactionTemplate` 的原因）與唯讀查詢分成兩個 Bean——後者能同時降低循環依賴風險。
- **基線對應**：對應 roadmap 「Low-Med｜JwtService 手刻 90 行 EC 數學；**AuthService 543 行待拆**」→ **仍開放且變長（543 → 569）**；其餘 4 個為 **新**。

---

#### ARCH-23（LOW）｜version 模組直接 import article 的 Markdown 渲染元件

- **file:line**：`blog-module-version/.../service/VersioningService.java:10-12`（import `ArticleMarkdownRenderer` / `ArticleTocCodec` / `RenderResult`）、`:51-56`（JavaDoc 自承「與 markdownRenderer 同屬 article 模組的無狀態元件，**跨模組注入的既有慣例**」）、`:283`
- **違反 / 情境**：`ai-docs/architecture.md`「Modules interact ONLY via Service Interfaces」。JavaDoc 用「既有慣例」正當化違規，正是 `judgment.md §2`「這裡先照舊模式寫」的訊號。
  退化情境：Markdown 渲染其實是**跨模組共用能力**（article 與 version 都要，未來 comment 的 renderer 也是同類），卻留在 article 模組裡；任何 article 的渲染管線變更會直接改變歷史版本的還原結果，且無 facade 介面可做契約約束。另註：`blog-module-comment` 有自己的 `CommentMarkdownRenderer` — 同一能力已經有兩份實作。
- **建議修法**：把 `ArticleMarkdownRenderer` / `ArticleTocCodec` / `RenderResult` 上移到 `blog-infrastructure`（或新的 `blog-markdown` 模組），article / version / comment 共用。純搬移，無行為變更。
- **基線對應**：對應 roadmap 「Medium｜reading/series/**version** 繞過 facade 直接 import article 內部 service」→ **仍開放**（本次補上「渲染屬共用能力、應上移而非開例外」的解讀）。

---

#### ARCH-24（LOW）｜MQ payload 帶明文驗證碼 / 重設 token 進入持久化 queue 與 DLQ

- **file:line**：`blog-module-user/.../model/event/UserRegisteredEvent.java:16`（`verificationToken`）、`UserPasswordResetRequestedEvent.java:14`（`resetToken`）；發送點 `blog-module-user/.../service/AuthService.java:145-148, 382-385, 452-455`；queue 為 durable：`UserRabbitMqConfig.java:32, 42, 56-57`（且設定了 DLQ）
- **違反 / 情境**：`ai-docs/security.md` 原則 8 的精神（憑證不得放在會被記錄/持久化的載體）。退化情境：token 明文落在 RabbitMQ 的磁碟訊息、management UI 的 queue 預覽、以及**失敗後長期滯留的 DLQ**。任何有 broker 讀權限的人（維運、備份還原者）都能取得可直接完成密碼重設的 token；DLQ 沒有 TTL（見 ARCH-18 對照），會無限期保留。
- **建議修法**：事件只帶 `userId` + `tokenId`，consumer 發信前用 `tokenId` 回 DB 換取 token（token 已存在 `verification_tokens` 表）；或至少為 `user.*` 兩個 queue 設 `x-message-ttl` 並限制 DLQ 保留期。
- **基線對應**：**新**。與安全維度**無重疊**（`be-review-security.md` 的 SEC-01~28 未涵蓋 MQ payload 面）；建議 Yuan 把此條交叉給安全維度複核嚴重度。

---

#### ARCH-25（LOW）｜red E2E 仍被 CI 排除（基線 TEST-10 零進度），且 e2e job 的 includes 覆寫連帶排除 ContextSmokeTest

- **file:line**：`blog-start/pom.xml:175-177`（`-Pe2e` 的 `<excludes combine.self="override"><exclude>**/red/*RedE2E.java</exclude>`）、`:183-199`（`red-e2e` profile）、`.github/workflows/ci.yml:74`（CI 只跑 `-Pe2e`，從不跑 `-Pred-e2e`）；被排除的 6 個 `@Test`：`blog-start/src/test/java/dowob/xyz/blog/e2e/red/{P0AuthLifecycleRedE2E, P0AuthorReviewRedE2E, P0ReaderInteractionRedE2E}.java`（各 2 個）
- **違反 / 情境**：findings.md **TEST-10** 已判定「un-gate 它是最便宜的防迴歸手段」。退化情境：唯一把**正確契約**寫成斷言的測試（雙擊按讚應冪等回 200）永遠不會在 CI 跑，等於 RACE-01 這類問題修好之後也沒有防迴歸網。附帶問題見 ARCH-03：`-Pe2e` 把 includes 覆寫成 `**/*E2E.java`，使 `ContextSmokeTest`（`*Test.java`）在 e2e job 同樣不會執行。
- **建議修法**：把 `-Pred-e2e` 加成 CI 的第三個 job（`continue-on-error: true` 起步，讓紅燈可見但不擋 merge），修好之後轉為必過。
- **基線對應**：findings.md **TEST-10** → **仍開放（零進度）**。

---

#### ARCH-26（LOW）｜series 的 API response DTO 內嵌 article 的 API response DTO

- **file:line**：`blog-module-series/.../model/dto/response/SeriesDetailResponse.java:4, 26`（`private List<ArticleSummaryResponse> articles;`）；來源 `SeriesService.java:330-332`
- **違反 / 情境**：`ai-docs/architecture.md`「Modules interact ONLY via Service Interfaces」——DTO 也是介面的一部分。退化情境：article 模組改 `ArticleSummaryResponse` 的任一欄位，**同時改變 `GET /api/v1/series/{slug}` 的回應 schema**，而 series 模組的作者不會知道；跨 repo 契約（前端）也因此有一條隱形的傳染路徑。對照組：`blog-module-recommend/.../model/dto/response/RecommendArticleResponse.java` 用的是 `infrastructure.facade.dto.ArticleSummaryInfo`（正確做法，已有先例）。
- **建議修法**：比照 recommend，改用 `facade.dto.ArticleSummaryInfo`，或在 series 模組定義自己的 `SeriesArticleItem`。
- **基線對應**：**新**（是 ARCH-04 的下游後果）。

---

#### ARCH-27（LOW / Info）｜`ArticleContentChangedEvent.RESTORED` 在唯一的 consumer 是 no-op

- **file:line**：`blog-module-article/.../event/ArticleContentChangedEvent.java:33-34`（`RESTORED` 枚舉值，JavaDoc 寫「version 模組訂閱時 no-op，但 search 訂同 article.updated 重 index」）；`blog-module-version/.../consumer/ArticleVersionConsumer.java:54-56`（`case RESTORED -> { /* no-op */ }`）；`version.snapshot` 是 `article.content.changed` 的唯一綁定（`VersionRabbitMqConfig.java:31-38`）
- **違反 / 情境**：`ai-docs/judgment.md §2` Producer/Consumer 配對規則的邊緣情形——routing key 有配對，但某個 action 值沒有任何行為。退化情境：不是 bug，但每次還原都送一則保證被丟棄的訊息（含 MQ 開銷 + `IdempotencyService` 缺席使它也不會被去重）。更值得注意的是文件承諾「search 靠 `article.updated` 重 index」——這條路徑的正確性取決於 restore 路徑真的有發 `article.updated`，屬於**隱式跨模組契約**，沒有測試覆蓋。
- **建議修法**：要嘛 producer 在 RESTORED 時不發此事件，要嘛把 `RESTORED` 從枚舉移除；並為「restore → search 重 index」補一個明確的 E2E 斷言（現在只靠註解承諾）。
- **基線對應**：**新**。相關 findings.md **DATA-03 / AUTH-01**（restore 副作用連鎖不完整）與 **SEC-02**（restore 繞過狀態守衛）——**同 security SEC-02**，本條只補「事件面的半配對」這一角度。

---

#### ARCH-28（LOW）｜客戶端 IP 解析在兩個模組各實作一次，無共用元件

- **file:line**：`blog-module-user/.../controller/AuthController.java:170-176`（手動取 `X-Forwarded-For` 最左節）、`blog-module-article/.../controller/ArticleController.java:305-319`（`request.getRemoteAddr()`，JavaDoc 宣稱「可避免手動解析 header 被偽造的安全風險」）
- **違反 / 情境**：`ai-docs/code-standards.md`「Consistency」＋ cross-cutting concern 應有單一實作。
  **架構視角（與安全維度的差異）**：`be-review-security.md` **SEC-01** 已從「XFF 可偽造 → 限流可繞過」的角度判為 HIGH。本條補的是結構性根因：這是一個**橫切關注點被複製到兩個模組的 Controller 私有方法裡，且兩份實作行為不同**——一份手動解析、一份靠框架，兩份對「什麼是可信 IP」的假設互相矛盾，而且都寫在 Controller（不是可測試、可統一替換的元件）。即使只修 SEC-01 的其中一處，另一處仍會漂移；沒有共用元件就沒有單一修補點。
- **建議修法**：在 `blog-infrastructure` 建 `ClientIpResolver`（SEC-01 建議移植的 `59c170d` 版本），兩個 Controller 改注入它；ARCH-03 的守衛可加一條「Controller 不得直接呼叫 `getRemoteAddr()` / 讀 `X-Forwarded-For`」。
- **基線對應**：**同 security SEC-01**（結構性解讀，不重複登記嚴重度）。

---

## 3. 基線對照表（架構維度）

> findings.md 無 ARCH 專章，架構層發現位於同 commit 的 `ai-docs/roadmap.md`「體檢發現索引」與工作包 C，加上 `ai-docs/backlog/`。以下逐筆判定。

### roadmap.md「體檢發現索引」＋工作包 C

| 基線項目 | 狀態 | 證據（develop @ f0e3cb1） |
|---|---|---|
| 「基礎設施已建好但沒用滿」總評 | **仍成立** | retry interceptor 死碼（ARCH-02）、`autoAckContainerFactory` 零使用者（ARCH-20）、api-contract 稽核腳本無 runner（ARCH-14） |
| IdempotencyService 僅 1/9 consumer 使用 | **部分修** | 現為 3/9：`SeriesArticleDeletedConsumer.java:56`、`TagUsageConsumer.java:77`、`ViewCountConsumer.java:57` |
| MQ retry interceptor 被手動 try/catch 抵銷 | **仍開放（0 進度）** | 9/9 consumer 全部手動 ack/nack，清單見 ARCH-02 |
| C1 抽共用 consumer wrapper | **仍開放** | 9 份 try/ack/catch/nack 樣板原封不動 |
| C2 consumer 改拋例外讓 retry 生效 | **仍開放** | 同上 |
| C3 所有事件補 `eventId` | **部分修（4/10）** | 清單見 ARCH-07 |
| C4 計數型 consumer 接 IdempotencyService | **部分修（2/3）** | view count ✅ / tag usage ✅ / **version snapshot ❌**（`ArticleContentChangedEvent` 無 `eventId`） |
| 可觀測性空白（無 micrometer/tracing） | **仍開放（0 進度）** | `application.yaml:91-95` 只開 health/info；pom 無 micrometer registry（ARCH-16） |
| Redis 為認證硬依賴無降級；Search 無 ES 故障降級 | **仍開放** | `JwtAuthenticationFilter` 仍以 Redis 為權威（同 security SEC-03 的相關敘述）；`SearchServiceImpl` 無 fallback 分支 |
| reading/series/version 繞過 facade 直接 import article 內部 service | **仍開放** | `BookmarkController.java:7,33`、`SeriesService.java:11,44`、`VersioningService.java:10-12`（ARCH-04 / ARCH-09 / ARCH-23） |
| 事件類位置不一致（部分 infrastructure、部分 article） | **仍開放且惡化** | 3 in infra / 6 in module / 1 in `module/user/model/event`；且 `ArticleTagEvent` **兩地重複定義**（ARCH-01 / ARCH-07） |
| JwtService 手刻 EC 數學；AuthService 543 行待拆 | **仍開放（變長）** | `AuthService.java` 現 **569 行 / 8 依賴**（ARCH-22） |
| prod 缺 `jwt.private-key` 時靜默生成金鑰 | **仍開放** | 同 security **SEC-13** |
| rate limiting 僅覆蓋 auth | **仍開放** | 同 security **SEC-23** |
| 前端 ESLint / **自建 OpenAPI 稽核未進 CI** | **仍開放（後端側）** | `ci.yml` 無 api-contract 步驟；`docs/api-contract/scripts/*.test.js` 無 `package.json`（ARCH-14） |
| 小勝利池：`FileController` 回傳 DTO | **半修** | `storagePath` 加了 `@JsonIgnore`（`FileMetadata.java:59`），但 `FileController.java:122,228` 仍回傳 entity（ARCH-08） |
| 小勝利池：架構文件斷鏈（architecture.md 僅列 4/10 模組） | **仍開放（分母變 13）** | `ai-docs/architecture.md:5`（ARCH-17） |
| 小勝利池：書籤/系列 2N+1（`getArticleSummariesByIds`） | **已修** | `ArticleQueryService` JavaDoc `:36-37` 說明用 `findIdsByUuids` 批量查；`SeriesService.java:332` 與 `BookmarkController.java:67` 皆走批次 API（效能面詳見 `be-review-performance.md`） |

### findings.md（本維度相關者）

| ID | 狀態 | 證據 |
|---|---|---|
| DATA-11（計數型 consumer 缺冪等；`ArticleTagEvent` 無 eventId） | **部分修** | `ArticleTagEvent` 已有 `eventId`（infra:23 / tag:21）；version snapshot 仍無（ARCH-07） |
| TEST-08（CrossModuleVersionIT mock 掉 facade） | **仍開放且範圍應擴大** | 四個 CrossModule IT 全部 mock 所有跨模組 facade（ARCH-10） |
| TEST-10（red E2E 未進 CI gate） | **仍開放（0 進度）** | `blog-start/pom.xml:175-177`、`ci.yml:74`（ARCH-25） |
| AUTH-08（BookmarkController 缺 null 檢查 → 500） | **仍開放** | `BookmarkController.java:40-41, 50-51`（ARCH-09 附帶確認） |
| T2（`try save catch DIVE` 冪等反模式）之「GlobalExceptionHandler 補兜底」 | **仍開放** | `GlobalExceptionHandler.java` 無 DIVE / OptimisticLocking handler（ARCH-11） |
| AUTH-01 / DATA-03（restore 副作用連鎖） | **仍開放** | 同 security **SEC-02**（本維度只補事件面半配對，見 ARCH-27） |
| AUTH-07 / FILE-01（storagePath 洩漏） | **安全面已修 / 架構面未修** | `@JsonIgnore` 已加，但 entity-as-DTO 的分層債仍在且漏了 `Tag.isNew`（ARCH-08） |
| RACE-*、DATA-01/02/04~10/12~14、FILE-02~07、XSS-*、DEP-*、FE-*、TEST-01~07/09/11/12 | **不適用（非架構維度）** | 屬併發 / 資料一致性 / 安全 / 前端 / 個別測試品質，見對應維度報告 |

### backlog

| 檔案 | 狀態 | 證據 |
|---|---|---|
| `2026-07-07-archunit-guards.md` | **仍開放（0 進度）** | 全 repo 零 ArchUnit 依賴與測試（ARCH-03） |
| `2026-07-29-article-file-binding-via-mq.md` | **前置已達成、本體仍開放；建議 Yuan 降優先序** | **循環依賴已真正打斷**：`ArticleLookupFacadeImpl.java:36-37` 只注入 `ArticleRepository`，`FileServiceImpl.java:93` 注入的是 `ArticleLookupFacade` 而非 `ArticleFacade`，回邊消失。但 `ArticleCommandSubService.java:45`（`ArticleFileBinder`）→ `ArticleFileBinder.java:43`（`FileFacade`）的同步耦合仍在。**必要性評估：中低**——原始動機（循環依賴）已由 PR #54 以更便宜的方式解決；剩下的只是「同步 best-effort 呼叫可改非同步」的整潔度收益，而改成 MQ 會引入新的最終一致性視窗（上傳圖→存草稿→綁定生效之間，`canRead` 會判為未綁定 → 作者以外看不到圖）。建議排在 ARCH-01/02/03 之後 |
| `2026-07-29-index-cache-rebuild-completeness.md` | **仍開放** | `reindexAll` 疊加不清；屬資料一致性維度（findings.md DATA-04），本維度不重複登記 |
| `2026-07-14-full-review-findings.md` M1（SeriesMapper 直讀 articles） | **仍開放且惡化** | 4 處 → 7 處（ARCH-13） |
| `2026-07-18-revocation-after-commit-guard.md` 的靜態 guard | **仍開放** | 併入 ArchUnit 待辦，同樣零進度 |

---

## 4. 檢查過但無 finding 的區域

| 區域 | 方法 | 結論 |
|---|---|---|
| **article ⇄ file 循環依賴是否真的斷了**（PR #54） | 讀 `ArticleLookupFacade.java`（全 62 行）、`ArticleLookupFacadeImpl.java`（全 68 行）、`FileServiceImpl.java` 的 8 個 `private final`、`ArticleFileBinder.java:43`、`FileFacadeImpl.java:27`，手工追兩條環的回邊 | ✅ **確認已斷**。`ArticleLookupFacadeImpl` 依賴閉包只有 `ArticleRepository`；`FileServiceImpl` 不再依賴 `ArticleFacade`。零 `@Lazy`、未開 `allow-circular-references`（`rg` 確認），是靠重構而非規避解決的 |
| **跨模組 import 全盤掃描** | `rg "^import dowob\.xyz\.blog\.module\." --type java -g '!**/src/test/**'` + awk 過濾同模組 | main 原始碼**只有 12 處**跨模組 import（version→article ×5、series→article ×5、reading→article ×2），全部已在 ARCH-04/23/26 及事件配對處登記。**沒有任何模組 import 他模組的 `repository` 或 entity `model`**（roadmap 稱的「編譯期零 repository/entity 跨模組引用」在 develop 上仍然成立） |
| **Transaction + MQ 時序**（`code-standards.md` §CRITICAL） | 對每個 `.java` 同時 grep `@Transactional` 與 `convertAndSend`，再逐一看呼叫鏈；另檢查 `ArticleCommandSubService` 的 4 個發事件方法是否帶 `@Transactional` | ✅ **全站正確**。`AuthService.java:128/145`、`:370/382`、`:431/452`、`FileServiceImpl.java:210/217` 全部是 `transactionTemplate` 先 commit 再 best-effort 發送；`ArticleCommandSubService` 的 `createArticle:70` / `updateArticle:144` / `deleteArticle:241` / `publishArticle:273` **皆未標 `@Transactional`**，事件在交易外發送。BUG-2026-001 FIN-2 未復發 |
| **Producer / Consumer 配對**（`judgment.md §2`） | 抽出 8 個 `*RabbitMqConfig` 的全部 exchange / queue / routing key 常數，對 9 個 `@RabbitListener` 與 8 個 `convertAndSend` 呼叫點做雙向比對 | 除 ARCH-05（`article.published` 孤兒 queue）外**全部配對**：`article.viewed`→ViewCount、`article.tagged`→TagUsage、`article.updated`/`article.deleted`/`article.published`→Search 三個 queue、`article.published`→Recommend、`article.deleted`→Series、`article.content.changed`→Version、`file.image.uploaded`→Thumbnail、`user.registered`→EmailVerification、`user.password.reset`→PasswordReset。無「有 consumer 無 producer」的反向缺口 |
| **DLQ 接線完整性** | grep `x-dead-letter-exchange` 於全部 queue 宣告 | ✅ 8 個模組的 queue **全部**設了 `blog.dlq` + `dead-letter`，與 `RabbitMqConfig:93-127` 宣告的 DLQ 拓撲一致，無孤兒 queue（重複問題見 ARCH-19，TTL 不一致見 ARCH-18） |
| **對外 ID 是否徹底 UUID 化** | `rg "Long" --type java -g '**/dto/response/**' -g '!**/test/**'` | ✅ 24 個 response DTO **零** `Long id` 欄位（僅 3 處命中是 JavaDoc 說明「不暴露內部 Long ID」，1 處是 `SearchIndexStatusResponse.documentCount` 的合理計數）。PR #50 的成果在 DTO 層守住了；唯二破口是 ARCH-08 的 entity 直出 |
| **`blog-common` shared kernel 純度** | `git ls-files blog-common/src/main/java` 全清單（21 檔） | ✅ 只有 `api/{dto,enums,errorcode,response}`、`constant`、`exception`、`util`，無任何模組專屬業務邏輯、無 Spring Data、無 repository。是乾淨的 shared kernel（唯一爭議是 5 個模組專屬 ErrorCode 放這裡，已記為 ARCH-15） |
| **Maven 模組依賴圖是否有環** | 抽出 14 個 `pom.xml` 的 `<dependencies>` 中所有 `blog-*` artifact | ✅ **建置期 DAG 無環**：common ← infrastructure ← {user, article, tag, file, search, recommend} ← {version, series, reading} ← comment ← start。注意 comment/reading/series/version 對 article 是**整模組**依賴（非只依賴 infrastructure），所以 Maven 攔不住 ARCH-04/23/26 那類違規——這是 ARCH-03 守衛 #3 的存在理由 |
| **Flyway migration 與 `ai-docs/schema.md` 同步**（CLAUDE.md §Schema Maintenance） | 列出 V1–V21 檔名，比對 `ai-docs/schema.md:553-577` 的 Migration Index | ✅ **完全同步**，21 筆一一對應且描述具體（含 V20 的「刻意不設 FK（跨模組邊界）」這種架構決策記錄）。這條制度規則是本 repo 執行得最好的一條 |
| **測試紀律**（`testing-standards.md`） | `rg "@Disabled\|Thread\.sleep" -g '**/src/test/**'`；`rg "void\s+[非ASCII]" -g '**/src/test/**'`；統計 `@Test` / `@DisplayName` | ✅ **1511 個 `@Test`、1594 個 `@DisplayName`**（覆蓋率 >100%，含 `@Nested`）；**零 `@Disabled`、零 `Thread.sleep`、零中文測試方法名**。命名與可讀性紀律極佳 |
| **測試分層與 Testcontainers 使用** | 逐模組統計 main class / test class / `*IT` / `@Test` 數；`rg "Testcontainers\|PostgreSQLContainer"` | 22 個 `*IT` **全部**用真 Testcontainers（非 mock DB），`AbstractE2ETest` 用 5 個真容器。**薄弱點（記錄但未獨立成 finding，因與 ARCH-12 同源）**：`blog-module-user`（32 main / 0 IT，僅 `AuthServiceIntegrationTest`）與 `blog-infrastructure`（32 main / 7 test class / 0 IT）是安全核心卻整合覆蓋最薄；`blog-module-article` 53 main class 只有 2 個 IT |
| **`@RestControllerAdvice` 是否多重/衝突** | `rg "ControllerAdvice" --type java -g '!**/test/**'` | ✅ 全 repo **唯一一個**（`GlobalExceptionHandler.java:33`），無多重 advice 的順序不確定問題；12 個具名 handler 全部回傳 `ResponseEntity<ApiResponse<Void>>` 並帶明示 status，符合 `code-standards.md` §Error Handling 的 CRITICAL 規則（缺口只有 ARCH-11 的兩個例外型別） |
| **profile 切分與條件式 Bean** | 讀 `application.yaml` / `-dev` / `-demo` / 測試側 `-e2e`；`rg "@Profile\|@ConditionalOn"` | profile 結構清楚（base 為 prod、dev/demo 各自覆寫、e2e 僅測試），且全 repo 只有 **3 個** 條件式 Bean（`IdempotencyAutoConfiguration:25`、兩個 mail consumer），代表 **bean 圖幾乎與 profile 無關** → ContextSmokeTest 只跑 e2e profile 的覆蓋損失很小（真正的問題是它根本不跑，見 ARCH-03）。`application-dev.yaml:1-3` 明確警告勿寫入真憑證，做法正確 |
| **Controller 業務邏輯普查** | 逐一檢視 21 個 Controller 的 handler body（含 3 個 >250 行者） | 除 `BookmarkController`（ARCH-09）外**大致乾淨**：`ArticleController:111-134`、`SeriesController`、`VersionController`、`UserController` 等皆為「解析 principal/role → 單一 service 呼叫 → 包 `ApiResponse`」。`SecurityUtils.resolveRole(authentication)` 是共用的、正確的抽象 |
| **重複工具方法普查** | 抽出全部 `private` 方法名，找跨模組同名者 | 只有兩組：`dlqArgs()`（6 模組，已記 ARCH-19）與 `toResponse()`（article/comment/reading 各一，屬各自 DTO 的合理 local mapper，**非重複**）。無散落的 util 複製 |
| **Jacoco 設定** | `pom.xml` `<build>` 全文 | 有 `prepare-agent` + `verify` 階段 report，CI 也上傳 artifact，但**無 `jacoco:check` 門檻規則** → 覆蓋率只被觀測不被強制。記錄為觀察，未獨立成 finding（在沒有覆蓋率目標共識前，加門檻反而會製造噪音；建議 Yuan 先決定是否要目標值） |

---

## 5. 給 Yuan 的 triage 建議（施工順序）

1. **先做 ARCH-03 的前置 ARCH-17**（補 `architecture.md` 的允許矩陣 + 載體放置表）——沒有條文就寫不出 ArchUnit 規則。半天工作量，解鎖後面一整串。
2. **ARCH-03 守衛 #1 #2 落地**（Transaction+MQ、`@PreAuthorize`）——`backlog/2026-07-07` 已經躺了兩個月，且這兩條在現有 codebase 上應該直接全綠（本次靜態檢查未發現違規），是零阻力的起手式。
3. **CI 兩行改動**：e2e job 加 `-Dcontext.smoke=true`（ARCH-03）、加第三個 `-Pred-e2e` job（ARCH-25）。成本近零，補回兩道已經寫好卻沒在跑的防線。
4. **ARCH-01 刪重複的 `ArticleTagEvent`**（改一個 import + 刪一個檔）與 **ARCH-05 刪孤兒 queue**（刪兩個 bean）——兩個都是幾分鐘的事，各消掉一顆定時炸彈。
5. **ARCH-11 補兩個 exception handler**——單點修掉基線一整類 500（RACE-01/03/04/08/15）。
6. **ARCH-02 的 consumer wrapper（roadmap C1/C2）** 是本報告最大的一塊工，但也是 roadmap 早已定案的 D2；建議與 ARCH-07（事件補 `eventId`）一起做，因為 wrapper 正好是掛 `IdempotencyService` 的位置。
7. ARCH-04/08/13/14/15 屬「持續性架構債」，適合在碰到相關模組時順手償還，不必單獨排期。
