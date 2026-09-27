# 交接：SEC-04（分頁夾界）與 ARCH-30（CASCADE FK）

- **建立日期**: 2026-09-06
- **狀態**: 待指派，兩項互相獨立、可分開進行
- **交接來源**: `feat/cross-module-set-predicate`（PR #71，已合併，merge commit `752de06`）
- **基準**: `develop` @ `752de06`

---

## 0. 先讀（專案硬規則）

| 規則 | 出處 | 對本任務的意義 |
|---|---|---|
| **TDD 強制** | `CLAUDE.md` | 先寫 failing test 跑到看見紅，才寫實作。不得先寫實作 |
| 測試輸出存檔 | `CLAUDE.md` | `2>&1 \| tee logs/<name>.log`；單一失敗先讀 surefire XML，不重跑全套 |
| **破壞性 migration 必問 Yuan** | `judgment.md` §5 | **ARCH-30 的 8 個 DROP FK 直接命中這條** |
| **API 契約變更必問 Yuan** | `judgment.md` §5 | SEC-04 的 filter／sort 參數屬新增（相容），但降 `MAX_SIZE` 是語意變更 |
| Public Endpoints 表增減必問 | `judgment.md` §5 | 兩項應該都不觸及，若觸及先停 |
| `architecture.md`／`security.md` 為提案制 | `maintenance.md` §2 | 改它們要 Yuan 簽核；`ai-docs/backlog/*` 可直接寫 |
| 界限要寫進程式 | `judgment.md` §9 | 別再只在 JavaDoc 標門檻 |
| 發現盲點模式回頭掃既有防線 | `judgment.md` §8 | 見本檔 §3 的教訓 |

Migration 慣例見 `flyway-convention.md`；新增 migration **必須同步更新 `ai-docs/schema.md`**（`CLAUDE.md` 明文）。

---

## 1. 項目 A：SEC-04 — 分頁 size 夾到 100

### 1.1 目標與現況

`findings.md` **SEC-04** 要求把分頁 `size` 夾在 `[1, 100]`。目前夾在 **`[1, 1000]`**。

現況（2026-09-06 落地，PR #71）：

- `blog-common/.../api/request/PageQuery.java` — record ＋ compact constructor，`MAX_SIZE = 1000`
- 8 個分頁端點已全數改收 `PageQuery`，query string 契約不變（仍是 `?page=&size=`）
- 上界是**型別保證**：record 欄位 final、所有建構路徑必經 canonical constructor，語法上無法繞過
- 預設值由各端點以 `sizeOrDefault(int)` 提供（4 個端點是 10、4 個是 20，刻意不統一——統一即 API 語意變更）

**所以「防 `size=100000` 拉垮伺服器」這件事已經做完了。** 剩下的 1000 → 100 是縱深防禦，不是止血。

### 1.2 前置依賴（關鍵，別跳過）

**降到 100 會造成資料靜默遺失**，因為前端不是伺服器端分頁。

已查證 `blog-web-v2-front-end/src/views/ArticleList.vue`：

```js
const filtered   = computed(() => filterAndSort(allArticles.value))   // 前端 filter + sort
const totalPages = computed(() => Math.ceil(filtered.value.length / PER_PAGE))
// 兩種瀏覽模式：pages（分頁器）與 infinite（無限捲動）
```

它以 `size=1000` 取回全量後，在**前端**完成 filter、sort、分頁。降到 100 的後果：

| 前端呼叫點 | 現行 size | 降到 100 的後果 |
|---|---|---|
| `ArticleList.vue` | 1000 | 只拿到 100 篇，分頁器卻宣稱「這就是全部」 |
| `AuthorView.vue` | 200 | 作者頁靜默少一半 |
| `TagView.vue` | 100 | 剛好卡邊界 |
| 其餘（Bookmarks／AdminReview／MyArticles／useComments） | 10–20 | 安全 |

**要點：前置不只是「改分頁」。** filter 與 sort 必須一起搬到後端，否則跨頁排序與跨頁篩選會壞掉。這是後端新增 query 契約 ＋ 前端列表頁重寫 ＋ 兩邊補測試的完整 feature。

### 1.3 建議的切法（三段，每段可獨立交付）

1. **後端加 filter／sort／分頁契約** — 新增 query 參數（category／keyword／sort），`MAX_SIZE` 不動。純新增、向下相容，前端不改也不會壞
2. **前端改用新契約** — `ArticleList` 與 `AuthorView` 改為伺服器端 filter＋sort＋分頁；注意 `infinite` 模式也要一起改（它靠 `page * PER_PAGE < filtered.length` 判斷還有沒有下一頁）
3. **降 `MAX_SIZE` 到 100** — 只有前兩段完成後才做，一行改動 ＋ 更新 `PageQuery` 的 JavaDoc 與 `findings.md` SEC-04 狀態

### 1.4 要 Yuan 拍板的

- 是否要做完整三段，還是只做第 1 段（後端準備）
- 第 3 段是「截斷」還是「回 400」——目前 `PageQuery` 是**截斷**（靜默）；回 400 較誠實但屬破壞性契約變更

### 1.5 相關檔案

- `blog-common/src/main/java/dowob/xyz/blog/common/api/request/PageQuery.java`
- `blog-common/src/test/java/dowob/xyz/blog/common/api/request/PageQueryTest.java`（含 3 個 MockMvc 綁定實測）
- 8 個 controller：Admin文章／文章列表／我的文章／留言／收藏／搜尋／系列／版本
- 前端：`blog-web-v2-front-end/src/views/ArticleList.vue`、`AuthorView.vue`、`src/api/articleService.ts`
- `ai-docs/findings.md` SEC-04、`ai-docs/backlog/2026-09-04-cross-module-set-predicate.md` §7.1

---

## 2. 項目 B：ARCH-30 — 8 條 CASCADE FK 與共用 Long PK

### 2.1 目標與現況

`findings.md` **ARCH-30**（OPEN，標註**刻意不修**）：跨模組耦合的真正載體是 schema 而非 SQL。

**8 條 FK 的確切分佈（已逐條查證）**：

| Migration | 表 | 指向 | 所屬模組 |
|---|---|---|---|
| `V1__init_schema.sql` | `comments` | `articles(id)` | comment |
| `V1__init_schema.sql` | `article_likes` | `articles(id)` | reading |
| `V6__add_categories.sql` | `article_categories` | `articles(id)` | article（模組內） |
| `V9__recreate_tags_uuid.sql` | `article_tags` | `articles(uuid)` | tag |
| `V14__add_reading_interactions.sql` | `user_bookmarks` | `articles(id)` | reading |
| `V14__add_reading_interactions.sql` | `user_highlights` | `articles(id)` | reading |
| `V14__add_reading_interactions.sql` | `user_reading_progress` | `articles(id)` | reading |
| `V16__add_article_versions...sql` | `article_versions` | `articles(id)` | version |

> **命名陷阱，別重複踩**：`grep "REFERENCES articles" migration/*.sql` 會看到 **9** 條，且 V1 的按讚表叫 `article_likes`、findings 卻寫 `user_article_likes`。兩者都不是錯：
> - V1 的 `article_tags` 已被 **V9 重建取代**，故現存正好 8 條
> - `article_likes` 於 **V15 改名**為 `user_article_likes`（`schema.md:254` 明載），findings 記的是現況
>
> 我在交接前已為此查證過一輪，結論是 **findings.md 的記載正確**。別再懷疑一次。

另一半：跨模組流通的貨幣是**資料庫主鍵 `Long`** 而非 UUID（`ArticleFacade.findIdByUuid` 回傳 PK，被寫進 `user_bookmarks.article_id`）。刪文章依賴 Postgres CASCADE 橫跨 5 個模組連鎖刪除。

### 2.2 前置依賴（backlog 明訂，目前**未滿足**）

> 「要動本條須先有可觀測性（**ARCH-16**），否則事件補償的漂移不可見（對照 DATA-01／DATA-04／DATA-10 皆為『副本對不上真相』）」

**ARCH-16 現況：仍 OPEN。** 2026-09-06 只補了一小塊：

- ✅ `micrometer-core` 已在 `blog-infrastructure` 的 classpath；`management.endpoints.web.exposure.include` 已含 `metrics`
- ✅ `BatchedQuery` 對輸入集合大小打點（`blog.batched.query.input.size`，tag `caller`）
- ✅ `/actuator/**` 已收緊至 `hasRole("ADMIN")`（`health`／`info` 仍公開，K3s probe 不受影響）
- ❌ **無 prometheus registry、無 scrape、無告警、無 DLQ 深度、無一致性計數器、無 tracing**

也就是說：現在有「一個指標可以查」，沒有「漂移會被主動發現」。拆 FK 之後靠事件補償，補償失敗仍然看不見——那正是 DATA-01／04／10 的重演形態。

**額外注意**：若要做 prometheus scrape，會撞到本輪剛加的 `/actuator/** → hasRole("ADMIN")`。scraper 是機器、不帶 JWT。三條路：ADMIN 服務帳號、來源 IP／network policy 限制、或 `management.server.port` 分離（Spring Boot 對此的標準解，我傾向這條）。infrastructure repo 在 `D:\backup\backup\程式\workspace\infrastructure`。

### 2.3 建議的切法

1. **先補 ARCH-16**（真正的前置）— prometheus registry ＋ k3s scrape ＋ DLQ 深度 ＋ 一致性計數器。跨 infrastructure repo
2. **改跨模組貨幣為 UUID** — ARCH-30 的另一半。**不動刪除語意，風險低得多**，且是拆服務方向的真實進展。可先於第 1 項做
3. **拆 FK** — 8 個 DROP FK migration ＋ 每模組刪除事件 consumer ＋ 補償機制。前置未滿足前不建議

### 2.4 要 Yuan 拍板的

- **8 個 DROP FK 是 `judgment.md` §5 的破壞性 migration，必須明確授權**
- 拆掉 CASCADE 後的刪除語意：改為同步事件、非同步事件、或軟刪除？三者的一致性保證與可觀測性需求差很多
- 是否先做第 2 項（UUID 貨幣化）——它獨立、風險低、不需等 ARCH-16

### 2.5 相關檔案

- `blog-db-migration/src/main/resources/db/migration/V1、V6、V9、V14、V16__*.sql`
- `ai-docs/schema.md`（真相版本；新增 migration 必須同步）
- `blog-infrastructure/.../facade/ArticleFacade.java` 的 `findIdByUuid`
- `ai-docs/findings.md` ARCH-30、ARCH-16、DATA-01／DATA-04／DATA-10

---

## 3. 交接者的提醒（省下重複踩坑）

### 3.1 行尾已根治，但切舊分支仍會遇到

`develop` @ `752de06` 起，全 repo 行尾統一為 LF（`git ls-files --eol` → 663 `i/lf` ＋ 2 binary，**零 CRLF**）。`.gitattributes` 已補 `* text=auto eol=lf`。

**但切到 `752de06` 之前的 commit／分支時**，會出現上百個「假 modified」，且 **`git checkout -- .` 無法丟棄**（git 每次 checkout 都依 attributes 重新產生同樣差異）。出路是 `git reset --hard <target>`，動手前先驗證：

```bash
git diff --ignore-cr-at-eol --quiet ; echo $?   # 0 = 零實質差異，安全
```

注意 `git diff --name-only` **不套用** `--ignore-cr-at-eol`，拿它驗證會得到錯誤結論（我踩過）。

`.git-blame-ignore-revs` 已建立，各自執行一次：
```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

### 3.2 Flyway checksum 不受行尾影響（已驗證，別再擔心）

`flyway-core` 11.7.2 的 `ChecksumCalculator` 以 `BufferedReader.readLine()` 逐行讀取後 `CRC32.update`，`readLine()` **不含行尾字元**。結論來自 bytecode（`javap -c` 可見 `readLine → String.getBytes → CRC32.update` 呼叫序列），非推論。

### 3.3 現有的三個 ArchUnit 守衛會擋你

`blog-start/src/test/java/dowob/xyz/blog/architecture/`：

| 守衛 | 規則 |
|---|---|
| **#5** `CrossModuleBoundaryTest` | mapper SQL（**含 XML mapper**）不得出現他模組業務表名 |
| **#6** `TransactionMqBoundaryTest` | `@Transactional` 涵蓋的方法內不得直接 `rabbitTemplate.convertAndSend` |
| **#7** `EndpointAuthorizationTest` | 取用 `@AuthenticationPrincipal` 的 handler 必須有 `@PreAuthorize`；豁免須綁定 `security.md` Public Endpoints 表 |

守衛 #6 與拆 FK 高度相關——**改為事件驅動刪除時，發送點必須在交易 commit 之後**（`TransactionTemplate` ＋ best-effort，見 `code-standards.md`）。守衛會擋住寫錯的版本，但它只看**直接**呼叫；經 `ArticleEventPublisher` 這類封裝層它看不到，別把它當成完整證明。

### 3.4 寫守衛時的鐵律

三個守衛都附**反向驗證**（故意違規的 fixture 證明抓得到）。原因：守衛掃不到違規有兩種可能——真的沒違規，或守衛壞了。只斷言 `violations` 為空無法區分。

守衛 #7 另有**防腐測試**（白名單每項必須仍是實際違規），守衛 #5 有**防假陰性測試**（斷言 XML 掃描確實掃到檔案，否則會因「什麼都沒掃」而恆綠）。

> 這條是血淚：守衛 #5 上線時對 XML mapper **完全失明**，是 PR #71 的 Codex review 抓到的。而諷刺的是，我在同一輪普查 `<foreach>` 時剛踩過「pattern 漏掉 XML mapper」的坑、還寫進了 bug report，卻沒回頭檢查既有守衛有沒有同一個盲點。已升格為 `judgment.md` §8 的反向面。

### 3.5 測試環境

本機無 Docker，Testcontainers 的 `*IT` 全部無法跑（`blog-module-file` 的 FAILURE 恆為此原因）。跑單元測試請用：

```bash
./mvnw -o test -fae -Dtest='!*IT,!*IntegrationTest' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

`blog-start` 會因 `blog-module-file` 失敗而被 skip，需單獨 `./mvnw -o test -pl blog-start` 才跑得到三個守衛。

---

## 4. 本輪已完成、不要重做的事

PR #71（34 commits，已合併）：

- `BatchedQuery` 原語＋12 處 IN 切批；1 處**明確禁止切批**（`findByTagIds` 帶 `DISTINCT + ORDER BY + LIMIT`）
- `PageQuery` 分頁上界型別保證（8 個端點）
- ArchUnit 守衛 #5（含 XML）／#6／#7
- `BatchedQuery` metrics 打點、actuator 收緊至 ADMIN
- 全 repo 行尾 renormalize（192 個 CRLF blob → 0）
- `architecture.md` 述詞三格分類與方案 B 否決範圍限定；`judgment.md` §8／§9
- `BUG-2026-003`、首次 `/review-bugs`

詳見 `ai-docs/backlog/2026-09-06-in-clause-batching-and-page-bounds.md`。
