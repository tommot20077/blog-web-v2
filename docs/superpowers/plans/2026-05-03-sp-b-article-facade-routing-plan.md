# SP-B: Article Facade Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 3 模組（comment / reading / series）對 ArticleService 的跨模組 inject 全部改用 ArticleFacade，徹底消除 SeriesFacadeImpl 的 @Lazy ArticleService setter injection。ArticleFacade 補 5 read + 5 write method。

**Architecture:** ArticleFacade 是跨模組對 article 模組的統一 entry point；ArticleFacadeImpl 在 article 模組內部用純 delegate 模式委派 ArticleService。@Lazy 移除原因：SeriesFacadeImpl 改 inject ArticleFacade（interface 在 infrastructure），跟 SeriesFacade interface 同層，不再形成 Spring DI 循環。

**Tech Stack:** Spring Boot, Spring Data JDBC, MyBatis, Lombok, JUnit 5, Mockito, Spring Security Test, Testcontainers (PostgreSQL + Redis).

**Spec:** `docs/superpowers/specs/2026-05-03-sp-b-article-facade-routing-design.md`

---

## File Map

### 修改檔案

```
blog-infrastructure/
└─ src/main/java/dowob/xyz/blog/infrastructure/facade/
   └─ ArticleFacade.java                                              MODIFY (加 10 method 宣告)

blog-module-article/
├─ src/main/java/dowob/xyz/blog/module/article/facade/
│  └─ ArticleFacadeImpl.java                                          MODIFY (加 10 delegate 實作)
└─ src/test/java/dowob/xyz/blog/module/article/facade/
   └─ ArticleFacadeImplTest.java                                       NEW or MODIFY (10 unit tests)

blog-module-comment/
├─ src/main/java/dowob/xyz/blog/module/comment/service/
│  └─ CommentService.java                                              MODIFY (inject + 3 個 call site)
└─ src/test/java/dowob/xyz/blog/module/comment/service/
   └─ CommentServiceTest.java                                          MODIFY (mock + verify)

blog-module-reading/
├─ src/main/java/dowob/xyz/blog/module/reading/
│  ├─ controller/ArticleLikeController.java                            MODIFY
│  ├─ controller/BookmarkController.java                               MODIFY
│  ├─ service/ArticleLikeService.java                                  MODIFY
│  ├─ service/HighlightService.java                                    MODIFY
│  ├─ service/ReadingProgressService.java                              MODIFY
│  └─ job/ReadingProgressFlushJob.java                                 MODIFY
└─ src/test/java/dowob/xyz/blog/module/reading/
   ├─ controller/ArticleLikeControllerIT.java                          MODIFY (@MockitoBean)
   ├─ controller/BookmarkControllerIT.java                             MODIFY (@MockitoBean)
   ├─ controller/ReadingProgressControllerIT.java                      MODIFY (@MockitoBean，如有)
   ├─ service/ArticleLikeServiceTest.java                              MODIFY (@Mock)
   ├─ service/HighlightServiceTest.java                                MODIFY (@Mock)
   ├─ service/ReadingProgressServiceTest.java                          MODIFY (@Mock)
   └─ job/ReadingProgressFlushJobTest.java                             MODIFY (@Mock)

blog-module-series/
├─ src/main/java/dowob/xyz/blog/module/series/
│  ├─ service/SeriesService.java                                       MODIFY (inject + 5 個 call site)
│  └─ facade/SeriesFacadeImpl.java                                     MODIFY (移除 @Lazy + 改 ArticleFacade)
└─ src/test/java/dowob/xyz/blog/module/series/
   └─ service/SeriesServiceTest.java                                   MODIFY (@Mock + verify)
```

### 新增檔案（僅 1 個 — 如不存在）

```
blog-module-article/src/test/java/dowob/xyz/blog/module/article/facade/
└─ ArticleFacadeImplTest.java                                          NEW (如既有 ArticleFacadeImpl 沒對應 unit test 才新建)
```

---

## Pre-Flight Notes

1. **Worktree**：`.worktrees/refactor-sp-b-article-facade-routing/`，base 在 develop（HEAD `12edc8b`，含 batch 4 + SP-A + roadmap）
2. **Maven**：`./mvnw.cmd`（Windows wrapper）
3. **測試輸出**：`./mvnw.cmd test ... 2>&1 | tee logs/<task>.log`
4. **Surefire 報告**：失敗時讀 `<module>/target/surefire-reports/TEST-*.xml`
5. **Commit 慣例**：Conventional Commits + 繁中描述 + Co-Authored-By 行
6. **TDD**：T3 寫 facade impl 時先寫 unit tests（Red → Green）；inject 改動（T4-T8）是純 refactor，跑既有 test 確認無 regression 即可
7. **既有 patterns 對齊：**
   - Spring `@RequiredArgsConstructor` lombok 自動 constructor inject
   - `@MockitoBean` for IT (Spring Boot 3.4+) / `@Mock` for unit
   - 既有 ArticleFacadeImpl 用 @Service + @RequiredArgsConstructor — 維持
8. **Article entity 路徑**：`dowob.xyz.blog.module.article.model.Article`（給 facade method signature 用）

---

## Task 1: ArticleFacade interface 加 10 method 宣告（5 read + 5 write）

**Files:**
- Modify: `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/ArticleFacade.java`

- [ ] **Step 1: Read 既有 ArticleFacade.java**

```bash
Read blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/ArticleFacade.java
```

確認既有 6 method 簽名 + import 區。

- [ ] **Step 2: 加 import**（如尚未含）

```java
import dowob.xyz.blog.module.article.model.Article;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
```

⚠ Article entity 是 article 模組的 class，但 infrastructure 模組可以 import — 因為 infrastructure 不依賴 article 模組（只是 type reference 在 interface signature 上）。但這建立了 article → infrastructure（既有）+ infrastructure 引用 article 類型 — Spring 不會建依賴環，因為 facade interface 在 infrastructure 編譯時 article 已 compile 好。

實際上 infrastructure 不依賴 article 模組（pom 上沒）。它怎麼引用 Article class？看既有 ArticleFacade interface 是否用到 Article 類型 — 既有的 `findAllPublishedForIndex()` 回傳 `List<ArticleIndexData>` 不是 Article。

⚠ **重要**：infrastructure 模組對 article 模組沒 pom 依賴，**不能 import Article**。

對應的解：
- 不要回傳 `Optional<Article>`
- 改回傳輕量 DTO（已有 `ArticleBasicInfo`，看是否夠用）
- 或：加 `Article` 到 infrastructure facade 子 package（如 `infrastructure.facade.dto.ArticleData`）

更好的做法：**用既有 ArticleBasicInfo 取代 Article**。看它含哪些欄位是否足夠 caller 用。

**Read** `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/dto/ArticleBasicInfo.java` 確認欄位。

如果 ArticleBasicInfo 不含 caller 需要的欄位（如 series_id / categoryId / status），需要：
- 擴 ArticleBasicInfo 加缺欄位
- 或新建 `ArticleEntityDto` 含完整欄位

**簡化決策：** 為 SP-B 範圍乾淨，**沿用 Article entity（直接 import）但驗證 infrastructure pom 是否能 import**。先試 import，若編譯 fail 再 fallback 用 DTO。

**驗證方式**：
```bash
grep -nE "blog-module-article" blog-infrastructure/pom.xml
```

如果有依賴，OK。如果沒有，**改用 DTO 路線**（infrastructure 既有 ArticleBasicInfo，需擴）。

⚠ 此 task 的 Step 2 要先做這個 spike — implementer 需要根據實際結果決定方向。

- [ ] **Step 3: 加 5 read method 宣告**

於 ArticleFacade interface（既有 6 method 之後）加：

```java
// ─── SP-B 新增 5 read method ───

/**
 * UUID → DB id（最高頻跨模組查詢）。
 *
 * @param articleUuid 文章公開 UUID
 * @return 文章資料庫主鍵；查無時 null
 */
Long findIdByUuid(UUID articleUuid);

/**
 * UUID → Article 完整 entity（給 series 加文章流程用）。
 *
 * @param articleUuid 文章公開 UUID
 * @return Article entity Optional
 */
Optional<Article> findByUuid(UUID articleUuid);

/**
 * DB id → Article 完整 entity（給 SeriesFacade.getSeriesNavigation 用 — 解 @Lazy）。
 *
 * @param articleId 文章資料庫主鍵
 * @return Article entity Optional
 */
Optional<Article> findById(Long articleId);

/**
 * 批次 id 查 article（給 ReadingProgressService 用）。
 *
 * @param articleIds 文章主鍵列表
 * @return Article entity 列表
 */
List<Article> findByIds(List<Long> articleIds);

/**
 * 撈 series 內 article 排序好（給 SeriesService.getSeriesDetail 用）。
 *
 * @param seriesId 系列主鍵
 * @return 該 series 內 article 按 series_position 排序
 */
List<Article> findBySeriesIdOrderByPosition(Long seriesId);
```

⚠ 如果 Step 2 結果是 infrastructure 無法 import Article，所有回傳 `Article` / `Optional<Article>` / `List<Article>` 改用 ArticleBasicInfo（或新 DTO）— spec 容許這個 fallback。

- [ ] **Step 4: 加 5 write method 宣告**

```java
// ─── SP-B 新增 5 write method（counter / 欄位 set，simple write）───

/**
 * comment 模組創建 comment 時連動 article.comment_count + 1。
 *
 * @param articleId 文章主鍵
 */
void incrementCommentCount(Long articleId);

/**
 * comment 模組刪除 comment 時連動 article.comment_count - 1。
 *
 * @param articleId 文章主鍵
 */
void decrementCommentCount(Long articleId);

/**
 * reading 模組 like article 時連動 article.like_count + 1。
 *
 * @param articleId 文章主鍵
 */
void incrementLikeCount(Long articleId);

/**
 * reading 模組 unlike article 時連動 article.like_count - 1。
 *
 * @param articleId 文章主鍵
 */
void decrementLikeCount(Long articleId);

/**
 * series 模組 add / remove article from series 時 set article.series_id + series_position。
 *
 * @param articleId      文章主鍵
 * @param seriesId       系列主鍵（remove 時傳 null）
 * @param seriesPosition 在 series 內的位置（remove 時傳 null）
 */
void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition);
```

- [ ] **Step 5: 編譯驗證**

```bash
./mvnw.cmd -pl blog-infrastructure -am compile 2>&1 | tee logs/t1-compile.log
```

Expected: BUILD SUCCESS。

如失敗（如 Article 找不到），對應改用 DTO（Step 2 fallback）。

- [ ] **Step 6: Commit**

```bash
git add blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/ArticleFacade.java
git commit -m "$(cat <<'EOF'
feat(infra): ArticleFacade 加 10 method 宣告（5 read + 5 write）

- Read: findIdByUuid / findByUuid / findById / findByIds / findBySeriesIdOrderByPosition
- Write: incrementCommentCount / decrementCommentCount /
        incrementLikeCount / decrementLikeCount / updateSeriesAssignment
- 涵蓋跨模組（comment/reading/series）對 ArticleService 的 9 個 method 類型
- 為 SP-B 後續 ArticleFacadeImpl delegate + 3 模組 inject 改動鋪路

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: ArticleFacadeImpl delegate 10 個新 method + 10 unit tests (TDD)

**Files:**
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/facade/ArticleFacadeImpl.java`
- Create or Modify: `blog-module-article/src/test/java/dowob/xyz/blog/module/article/facade/ArticleFacadeImplTest.java`

- [ ] **Step 1: Read 既有 ArticleFacadeImpl + 對應 Test**

```bash
Read blog-module-article/src/main/java/dowob/xyz/blog/module/article/facade/ArticleFacadeImpl.java
```

確認：
- @Service / @RequiredArgsConstructor 模式
- 既有 inject（articleService / articleMapper / userFacade / etc.）
- 既有 6 method 實作風格

```bash
ls blog-module-article/src/test/java/dowob/xyz/blog/module/article/facade/
```

如有 ArticleFacadeImplTest 看既有 test 風格；無則新建。

- [ ] **Step 2: 寫 10 個 unit tests（先 RED）**

於 `ArticleFacadeImplTest.java` 加（如新建檔，含完整 setup）：

```java
package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.service.ArticleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleFacadeImplTest {

    @Mock private ArticleService articleService;
    // 既有其他 inject 依需要加 @Mock（看 ArticleFacadeImpl 既有 dependency）
    @InjectMocks private ArticleFacadeImpl facade;

    // ─── 5 read method delegate verify ───

    @Test
    void findIdByUuid_delegatesToArticleService() {
        UUID uuid = UUID.randomUUID();
        when(articleService.findIdByUuid(uuid)).thenReturn(100L);

        Long result = facade.findIdByUuid(uuid);

        assertThat(result).isEqualTo(100L);
        verify(articleService).findIdByUuid(uuid);
    }

    @Test
    void findByUuid_delegatesToArticleService() {
        UUID uuid = UUID.randomUUID();
        Article article = new Article();
        article.setId(100L);
        article.setUuid(uuid);
        when(articleService.findByUuid(uuid)).thenReturn(Optional.of(article));

        Optional<Article> result = facade.findByUuid(uuid);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(100L);
        verify(articleService).findByUuid(uuid);
    }

    @Test
    void findById_delegatesToArticleService() {
        Article article = new Article();
        article.setId(100L);
        when(articleService.findById(100L)).thenReturn(Optional.of(article));

        Optional<Article> result = facade.findById(100L);

        assertThat(result).isPresent();
        verify(articleService).findById(100L);
    }

    @Test
    void findByIds_delegatesToArticleService() {
        Article a1 = new Article(); a1.setId(1L);
        Article a2 = new Article(); a2.setId(2L);
        when(articleService.findByIds(List.of(1L, 2L))).thenReturn(List.of(a1, a2));

        List<Article> result = facade.findByIds(List.of(1L, 2L));

        assertThat(result).hasSize(2);
        verify(articleService).findByIds(List.of(1L, 2L));
    }

    @Test
    void findBySeriesIdOrderByPosition_delegatesToArticleService() {
        Article a1 = new Article(); a1.setId(1L);
        when(articleService.findBySeriesIdOrderByPosition(50L)).thenReturn(List.of(a1));

        List<Article> result = facade.findBySeriesIdOrderByPosition(50L);

        assertThat(result).hasSize(1);
        verify(articleService).findBySeriesIdOrderByPosition(50L);
    }

    // ─── 5 write method delegate verify ───

    @Test
    void incrementCommentCount_delegatesToArticleService() {
        facade.incrementCommentCount(100L);

        verify(articleService).incrementCommentCount(100L);
    }

    @Test
    void decrementCommentCount_delegatesToArticleService() {
        facade.decrementCommentCount(100L);

        verify(articleService).decrementCommentCount(100L);
    }

    @Test
    void incrementLikeCount_delegatesToArticleService() {
        facade.incrementLikeCount(100L);

        verify(articleService).incrementLikeCount(100L);
    }

    @Test
    void decrementLikeCount_delegatesToArticleService() {
        facade.decrementLikeCount(100L);

        verify(articleService).decrementLikeCount(100L);
    }

    @Test
    void updateSeriesAssignment_delegatesToArticleService() {
        facade.updateSeriesAssignment(100L, 50L, 3);

        verify(articleService).updateSeriesAssignment(100L, 50L, 3);
    }
}
```

⚠ 既有 ArticleFacadeImpl 還有其他 dependency（如 articleMapper / userFacade），這些 test 不需要 mock 它們因為新 method delegate 不用到。但 @InjectMocks 對 missing dependency 會 inject null — 多數情況 OK；如果 facade 有 constructor validation 對 null 報錯，補對應 @Mock。

- [ ] **Step 3: Run RED**

```bash
./mvnw.cmd -pl blog-module-article -am test -Dtest=ArticleFacadeImplTest 2>&1 | tee logs/t2-red.log
```

Expected: 編譯失敗（10 個新 method 在 impl 還沒實作）。

- [ ] **Step 4: 加 10 個 delegate 實作到 ArticleFacadeImpl**

於既有 ArticleFacadeImpl class 加：

```java
import dowob.xyz.blog.module.article.model.Article;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// 既有 6 method 之後加 ─── SP-B 新增 ───

@Override
public Long findIdByUuid(UUID articleUuid) {
    return articleService.findIdByUuid(articleUuid);
}

@Override
public Optional<Article> findByUuid(UUID articleUuid) {
    return articleService.findByUuid(articleUuid);
}

@Override
public Optional<Article> findById(Long articleId) {
    return articleService.findById(articleId);
}

@Override
public List<Article> findByIds(List<Long> articleIds) {
    return articleService.findByIds(articleIds);
}

@Override
public List<Article> findBySeriesIdOrderByPosition(Long seriesId) {
    return articleService.findBySeriesIdOrderByPosition(seriesId);
}

@Override
public void incrementCommentCount(Long articleId) {
    articleService.incrementCommentCount(articleId);
}

@Override
public void decrementCommentCount(Long articleId) {
    articleService.decrementCommentCount(articleId);
}

@Override
public void incrementLikeCount(Long articleId) {
    articleService.incrementLikeCount(articleId);
}

@Override
public void decrementLikeCount(Long articleId) {
    articleService.decrementLikeCount(articleId);
}

@Override
public void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition) {
    articleService.updateSeriesAssignment(articleId, seriesId, seriesPosition);
}
```

⚠ 確認 ArticleFacadeImpl 已 inject `private final ArticleService articleService;`。如沒，加（@RequiredArgsConstructor 自動處理）。

- [ ] **Step 5: Run GREEN**

```bash
./mvnw.cmd -pl blog-module-article -am test -Dtest=ArticleFacadeImplTest 2>&1 | tee logs/t2-green.log
```

Expected: 10 tests pass（新加 + 既有可能也有 test）。

- [ ] **Step 6: 跑 article 模組所有 test 確認沒打壞**

```bash
./mvnw.cmd -pl blog-module-article -am test 2>&1 | tee logs/t2-article-tests.log
```

Expected: 既有 article 模組 tests + 10 new = 全綠。

- [ ] **Step 7: Commit**

```bash
git add blog-module-article/src/main/java/dowob/xyz/blog/module/article/facade/ArticleFacadeImpl.java \
        blog-module-article/src/test/java/dowob/xyz/blog/module/article/facade/ArticleFacadeImplTest.java
git commit -m "$(cat <<'EOF'
feat(article): ArticleFacadeImpl delegate 10 個新 method + unit tests

- 純 delegate 模式：每個新 method 都委派到 articleService
- 10 個 unit tests 驗 delegate 行為（5 read + 5 write）
- ArticleFacadeImpl 在 article 模組內部 inject ArticleService 不算跨模組

⚠ ArticleFacade interface 在 infrastructure / impl 在 article module
   後續 task 4-8 將 3 模組改 inject ArticleFacade

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Comment 模組改 inject ArticleFacade

**Files:**
- Modify: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/service/CommentService.java`
- Modify: `blog-module-comment/src/test/java/dowob/xyz/blog/module/comment/service/CommentServiceTest.java`

- [ ] **Step 1: Read CommentService 既有 inject + call sites**

```bash
Read blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/service/CommentService.java
grep -n "articleService" blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/service/CommentService.java
```

確認 inject 行 + 3 個 call site（findIdByUuid / incrementCommentCount / decrementCommentCount）。

- [ ] **Step 2: 修 CommentService inject 與 call sites**

於 CommentService class：

```java
// import 改：
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
// 移除：
import dowob.xyz.blog.module.article.service.ArticleService;

// inject 改：
private final ArticleFacade articleFacade;
// 取代：
// private final ArticleService articleService;

// 3 個 call site 改：
articleFacade.findIdByUuid(articleUuid);     // 取代 articleService.findIdByUuid
articleFacade.incrementCommentCount(articleId);     // 取代 articleService.incrementCommentCount
articleFacade.decrementCommentCount(c.getArticleId());     // 取代 articleService.decrementCommentCount
```

⚠ 用 Edit tool 對應改。注意 @RequiredArgsConstructor 會自動產生 constructor 包含 articleFacade。

- [ ] **Step 3: 修 CommentServiceTest**

於 CommentServiceTest：

```java
// import 改
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
// 移除 import dowob.xyz.blog.module.article.service.ArticleService;

// @Mock 改
@Mock private ArticleFacade articleFacade;
// 取代 @Mock private ArticleService articleService;

// 所有 when(articleService.xxx)... 改 when(articleFacade.xxx)...
// 所有 verify(articleService).xxx... 改 verify(articleFacade).xxx...
```

⚠ Edit tool 用 `replace_all` 能批量改 `articleService` → `articleFacade`，但注意 `articleService` 變數名 vs `ArticleService` type — 兩個都要改。

具體 sed pattern：
- `private ArticleService articleService` → `private ArticleFacade articleFacade`
- `articleService.` → `articleFacade.`
- `import ...ArticleService;` 移除
- `import ...ArticleFacade;` 加（infrastructure path）

- [ ] **Step 4: install + Run comment 模組所有 tests**

```bash
./mvnw.cmd -pl blog-module-article -am install -DskipTests
./mvnw.cmd -pl blog-module-comment test 2>&1 | tee logs/t3-comment-tests.log
```

Expected: 既有 comment 模組 67 tests 全綠（純 inject refactor）。

- [ ] **Step 5: Commit**

```bash
git add blog-module-comment/src/
git commit -m "$(cat <<'EOF'
refactor(comment): CommentService 改 inject ArticleFacade 取代 ArticleService

- 移除跨模組直接 inject ArticleService（anti-pattern）
- 3 個 call site 改用 ArticleFacade：findIdByUuid / incrementCommentCount / decrementCommentCount
- CommentServiceTest 對應 mock 改為 ArticleFacade
- 既有 67 tests 全綠

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Reading 模組 Service Layer 改 inject ArticleFacade

**Files:**
- Modify: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/service/ArticleLikeService.java`
- Modify: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/service/HighlightService.java`
- Modify: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/service/ReadingProgressService.java`
- Modify: 對應 3 個 unit test

- [ ] **Step 1: 修 ArticleLikeService**

```java
// import 改
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
// 移除 ArticleService import

// inject 改
private final ArticleFacade articleFacade;
// 取代 private final ArticleService articleService;

// 2 處 call site
articleFacade.incrementLikeCount(articleId);
articleFacade.decrementLikeCount(articleId);
```

對應修 `ArticleLikeServiceTest`：mock 改 ArticleFacade，verify 改。

- [ ] **Step 2: 修 HighlightService**

```java
// import 改 + inject 改
private final ArticleFacade articleFacade;

// 2 處 call site
articleFacade.findIdByUuid(articleUuid);
```

對應修 `HighlightServiceTest`。

- [ ] **Step 3: 修 ReadingProgressService**

```java
// import 改 + inject 改
private final ArticleFacade articleFacade;

// 3 處 call site（findIdByUuid ×2 + findByIds ×1）
articleFacade.findIdByUuid(articleUuid);
articleFacade.findByIds(articleIds);
```

對應修 `ReadingProgressServiceTest`。

- [ ] **Step 4: install + Run reading 模組 tests**

```bash
./mvnw.cmd -pl blog-module-article -am install -DskipTests
./mvnw.cmd -pl blog-module-reading test 2>&1 | tee logs/t4-reading-tests.log
```

Expected: 既有 reading 模組 tests 全綠（IT 暫時可能 fail — controller 還沒改完，下個 task 處理）。

⚠ 如果 reading 模組 controller 跟 service 共用 application context（IT 環境），controller 的 IT 可能也要改 mock。先看哪些 IT fail，T5 統一改。

- [ ] **Step 5: Commit**

```bash
git add blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/service/ \
        blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/service/
git commit -m "$(cat <<'EOF'
refactor(reading): Service layer 改 inject ArticleFacade（3 個 service）

- ArticleLikeService: incrementLikeCount / decrementLikeCount → articleFacade
- HighlightService: findIdByUuid → articleFacade
- ReadingProgressService: findIdByUuid / findByIds → articleFacade
- 對應 3 個 unit test mock 改 ArticleFacade

⚠ Controller / Job 改動在 T5（同模組 IT 啟動時可能有 mock 殘留）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Reading 模組 Controller / Job Layer 改 inject ArticleFacade

**Files:**
- Modify: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/controller/ArticleLikeController.java`
- Modify: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/controller/BookmarkController.java`
- Modify: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/job/ReadingProgressFlushJob.java`
- Modify: 對應 IT / unit test

- [ ] **Step 1: 修 ArticleLikeController**

```java
// import 改 + inject 改
private final ArticleFacade articleFacade;

// 1 處 call site
articleFacade.findIdByUuid(articleUuid);
```

對應 `ArticleLikeControllerIT`：`@MockitoBean ArticleService` → `@MockitoBean ArticleFacade`，`when` 改。

- [ ] **Step 2: 修 BookmarkController**

```java
// 既有兩個 inject 都保留：
private final ArticleFacade articleFacade;        // ← 改自 ArticleService
private final ArticleQueryService articleQueryService;     // 保留（SP-B 範圍不動 ArticleQueryService）

// 2 處 call site
articleFacade.findIdByUuid(articleUuid);
```

對應 `BookmarkControllerIT`：mock 改。

⚠ ArticleQueryService 跨模組 inject 留待未來 SP-X — SP-B 不動。

- [ ] **Step 3: 修 ReadingProgressFlushJob**

```java
// import 改 + inject 改
private final ArticleFacade articleFacade;

// 1 處 call site
articleFacade.findIdByUuid(articleUuid);
```

對應 `ReadingProgressFlushJobTest`：mock 改。

- [ ] **Step 4: install + Run reading 模組 tests**

```bash
./mvnw.cmd -pl blog-module-article -am install -DskipTests
./mvnw.cmd -pl blog-module-reading test 2>&1 | tee logs/t5-reading-tests.log
```

Expected: 既有 reading 模組 66 tests 全綠（unit + IT）。

- [ ] **Step 5: Commit**

```bash
git add blog-module-reading/src/
git commit -m "$(cat <<'EOF'
refactor(reading): Controller / Job layer 改 inject ArticleFacade

- ArticleLikeController: findIdByUuid → articleFacade
- BookmarkController: findIdByUuid → articleFacade（ArticleQueryService 保留 — 留 SP-X）
- ReadingProgressFlushJob: findIdByUuid → articleFacade
- 對應 IT / unit test mock 改 @MockitoBean ArticleFacade

reading 模組 ArticleService 跨模組 inject 全清完
既有 66 tests 全綠

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Series 模組 SeriesService 改 inject ArticleFacade

**Files:**
- Modify: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/service/SeriesService.java`
- Modify: `blog-module-series/src/test/java/dowob/xyz/blog/module/series/service/SeriesServiceTest.java`

- [ ] **Step 1: 修 SeriesService**

Read 既有 inject + call sites。

```bash
grep -n "articleService" blog-module-series/src/main/java/dowob/xyz/blog/module/series/service/SeriesService.java
```

預期看到 inject 行 + 5 處 call site：
- `articleService.findByUuid` (×2)
- `articleService.updateSeriesAssignment` (×2)
- `articleService.findBySeriesIdOrderByPosition` (×1)

```java
// import 改
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
// 移除 import ArticleService

// inject 改
private final ArticleFacade articleFacade;

// 5 處 call site 全改
articleFacade.findByUuid(articleUuid);
articleFacade.updateSeriesAssignment(articleId, seriesId, position);
articleFacade.findBySeriesIdOrderByPosition(row.getId());
// etc.
```

- [ ] **Step 2: 修 SeriesServiceTest**

5 處 mock / verify 對應改：
- `@Mock ArticleService articleService` → `@Mock ArticleFacade articleFacade`
- `when(articleService.xxx)` → `when(articleFacade.xxx)`
- `verify(articleService).xxx` → `verify(articleFacade).xxx`

- [ ] **Step 3: install + Run series 模組 tests（含 IT）**

```bash
./mvnw.cmd -pl blog-module-article -am install -DskipTests
./mvnw.cmd -pl blog-module-series test 2>&1 | tee logs/t6-series-tests.log
```

Expected: 既有 36+ tests 全綠（unit + Controller IT + Cross IT）。

⚠ SeriesControllerIT 也可能有 `@MockitoBean ArticleService` — 對應改。

⚠ 注意：SeriesFacadeImpl 還沒改（仍 @Lazy ArticleService），下個 task 處理。

- [ ] **Step 4: Commit**

```bash
git add blog-module-series/src/main/java/dowob/xyz/blog/module/series/service/SeriesService.java \
        blog-module-series/src/test/java/dowob/xyz/blog/module/series/service/SeriesServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(series): SeriesService 改 inject ArticleFacade

- 5 個 call site 改：findByUuid (×2) / updateSeriesAssignment (×2) / findBySeriesIdOrderByPosition
- SeriesServiceTest mock 對應改為 ArticleFacade
- SeriesController IT @MockitoBean 也對應改

⚠ SeriesFacadeImpl 還用 @Lazy ArticleService — T7 處理

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: SeriesFacadeImpl 移除 @Lazy ArticleService → 改 inject ArticleFacade

**Files:**
- Modify: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/facade/SeriesFacadeImpl.java`

- [ ] **Step 1: Read SeriesFacadeImpl 既有結構**

```bash
Read blog-module-series/src/main/java/dowob/xyz/blog/module/series/facade/SeriesFacadeImpl.java
```

預期看到：
```java
@Setter(onMethod_ = {@Autowired, @Lazy})
private ArticleService articleService;
// ...
articleService.findById(articleId);
```

- [ ] **Step 2: 改用 ArticleFacade constructor inject**

```java
// import 改 — 加：
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.article.model.Article;
// import 移除：
import dowob.xyz.blog.module.article.service.ArticleService;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

// inject 改 — 從 setter inject 改 constructor inject：
// Before:
// @Setter(onMethod_ = {@Autowired, @Lazy})
// private ArticleService articleService;

// After:
private final ArticleFacade articleFacade;
// (透過 @RequiredArgsConstructor 既有 annotation 自動生成 constructor)

// call site 改：
articleService.findById(articleId);  // ← Old
articleFacade.findById(articleId);   // ← New
```

⚠ class 開頭的 @RequiredArgsConstructor 既有應該存在；如沒有，加。Lombok 的 @RequiredArgsConstructor 會把所有 final fields 包進 constructor。

- [ ] **Step 3: 移除不再需要的 import**

確認 imports 清單裡不再有：
- `import lombok.Setter;`
- `import org.springframework.beans.factory.annotation.Autowired;`
- `import org.springframework.context.annotation.Lazy;`
- `import dowob.xyz.blog.module.article.service.ArticleService;`

如果 `Autowired` 在其他地方還用到，保留 — 但通常 SeriesFacadeImpl 只在 @Setter 用過。

- [ ] **Step 4: install + Run series 模組 tests**

```bash
./mvnw.cmd -pl blog-module-article -am install -DskipTests
./mvnw.cmd -pl blog-module-series test 2>&1 | tee logs/t7-series-tests.log
```

Expected: 36+ tests 全綠。

⚠ 如 SeriesFacadeImpl 沒對應 unit test（既有），跑 SeriesControllerIT / CrossModuleSeriesIT 確認 getSeriesNavigation 流程仍正確（既有 IT 應 cover）。

- [ ] **Step 5: 全 codebase 驗 @Lazy 已消除**

```bash
grep -rn "@Lazy\|@Setter.*Lazy\|Lazy.*ArticleService" blog-module-*/src/main/java/ blog-infrastructure/src/main/java/ 2>&1 | head -10
```

Expected: 0 行（main code 不該再有 @Lazy 對 ArticleService 的 hack）。

⚠ 如有其他 @Lazy 用於正當理由（如 lazy bean initialization），看具體情況保留。本 grep 重點是「@Lazy + ArticleService」組合應為 0。

- [ ] **Step 6: Commit**

```bash
git add blog-module-series/src/main/java/dowob/xyz/blog/module/series/facade/SeriesFacadeImpl.java
git commit -m "$(cat <<'EOF'
refactor(series): SeriesFacadeImpl 移除 @Lazy ArticleService → ArticleFacade constructor inject

- 從 @Setter(@Autowired, @Lazy) ArticleService 改為 final ArticleFacade
- @RequiredArgsConstructor 自動生成 constructor
- findById call 改用 articleFacade
- imports 清理：移除 @Setter / @Autowired / @Lazy / ArticleService
- 全 codebase grep @Lazy 對 ArticleService 結果為 0

SeriesFacadeImpl ↔ ArticleService 循環依賴解除
（透過 ArticleFacade interface 在 infrastructure 層解耦）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: 全模組驗收 + sanity test

**Files:**（無修改，純驗證）

- [ ] **Step 1: 全 codebase grep verify**

確認 SP-B Done definition 達成：

```bash
# 1. ArticleService 跨模組 inject 應只剩 article 模組內部
grep -rn "private final ArticleService\|ArticleService articleService" \
    blog-module-comment/src/main/java/ \
    blog-module-reading/src/main/java/ \
    blog-module-series/src/main/java/ \
    blog-module-version/src/main/java/ \
    2>&1 | head -10
```

Expected: 0 行（4 個模組 main code 不該再 inject ArticleService）。

```bash
# 2. @Lazy 應全消除
grep -rn "@Lazy\|@Setter.*Lazy" blog-module-*/src/main/java/ blog-infrastructure/src/main/java/ 2>&1 | head -10
```

Expected: 0 行。

```bash
# 3. ArticleFacade 對應 inject 已普及
grep -rn "private final ArticleFacade\|ArticleFacade articleFacade" \
    blog-module-comment/src/main/java/ \
    blog-module-reading/src/main/java/ \
    blog-module-series/src/main/java/ \
    2>&1 | wc -l
```

Expected: 9 行（comment 1 + reading 6 + series 2）。

如 grep 顯示有殘留，回頭找對應 task 修。

- [ ] **Step 2: 跑 SP-B affected 全模組 tests**

```bash
./mvnw.cmd -pl blog-infrastructure,blog-module-article,blog-module-comment,blog-module-reading,blog-module-series test 2>&1 | tee logs/t8-all.log | grep -E "Tests run:|BUILD" | tail -15
```

Expected: 全綠：
- infrastructure 76+
- article 243+ (含 10 ArticleFacadeImpl unit tests = 253+)
- comment 67
- reading 66
- series 36+ (SP-A 補的 cross IT 仍綠)

- [ ] **Step 3: 跑全 modules sanity test**（保險用）

```bash
./mvnw.cmd test 2>&1 | tee logs/t8-full-sanity.log | grep -E "BUILD|FAIL" | tail -10
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit verification log（如想保留）**

如果 grep 結果想留作 PR description 證據：

```bash
echo "=== SP-B Done verification ===" > logs/sp-b-done.log
echo "ArticleService 跨模組 inject (應為 0):" >> logs/sp-b-done.log
grep -rn "private final ArticleService" blog-module-{comment,reading,series,version}/src/main/java/ >> logs/sp-b-done.log
echo "@Lazy ArticleService (應為 0):" >> logs/sp-b-done.log
grep -rn "@Lazy.*ArticleService\|@Setter.*Lazy" blog-module-*/src/main/java/ >> logs/sp-b-done.log
echo "ArticleFacade 跨模組 inject (應為 9 處):" >> logs/sp-b-done.log
grep -rn "private final ArticleFacade" blog-module-{comment,reading,series}/src/main/java/ >> logs/sp-b-done.log
```

但這 log 不要 commit 進 git（在 logs/ 目錄會被 .gitignore 阻擋）。

⚠ 本 task 通常不產生新 commit — 純 verification。如果 grep 找到漏網之魚，回頭 patch 並 commit。

---

## Self-Review Checklist

實作完成後請勾選：

- [ ] ArticleFacade 加 10 個 method 宣告（5 read + 5 write）
- [ ] ArticleFacadeImpl 加 10 個 delegate 實作 + 10 unit tests 全綠
- [ ] CommentService inject 改 ArticleFacade，3 個 call site 對應改
- [ ] ArticleLikeService / HighlightService / ReadingProgressService inject 改 ArticleFacade
- [ ] ArticleLikeController / BookmarkController / ReadingProgressFlushJob inject 改 ArticleFacade
- [ ] SeriesService inject 改 ArticleFacade，5 個 call site 對應改
- [ ] SeriesFacadeImpl 移除 @Lazy ArticleService 改 ArticleFacade
- [ ] 全 codebase grep `private final ArticleService` 跨模組為 0
- [ ] 全 codebase grep `@Lazy.*ArticleService` 為 0
- [ ] 全 codebase grep `private final ArticleFacade` 跨模組為 9 處
- [ ] 5 個 affected modules tests 全綠
- [ ] 既有 cross-module IT（CrossModuleSeriesIT 等）仍綠

---

## 後續批次

- **SP-D（events-and-helpers-cleanup）**：TagFacade.deleteArticleTags 改 event；AutoSnapshotPolicy / VersioningService 改 inject ArticleFacade（用 SP-B 補的 method）；SecurityUtils.isAdmin 提取到 infrastructure
- **SP-C（article-service-split）**：ArticleServiceImpl 1018 行 god class 拆分
