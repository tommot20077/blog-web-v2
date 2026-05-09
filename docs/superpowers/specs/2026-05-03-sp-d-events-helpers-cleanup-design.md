# SP-D：Events and Helpers Cleanup — 設計文件

> **Status:** Drafted by brainstorm with Yuan, awaiting user spec review.
>
> **Roadmap reference:** `docs/superpowers/specs/2026-05-03-architecture-decoupling-roadmap.md` §5.3
>
> **Worktree:** `.worktrees/refactor-sp-d-events-helpers-cleanup`
>
> **Branch:** `refactor/sp-d-events-helpers-cleanup` (from `develop@e2df4c1` 含 SP-B merge)

---

## 1. Goal & 範圍

對齊 roadmap §5.3。SP-D 是 4 個 sub-projects 第 3 個，承接 SP-B 完整建立的 ArticleFacade routing pattern，負責收尾零碎的跨模組依賴債：

1. **解掉 version 模組對 article 模組 `ArticleRepository` 的跨模組 inject**：AutoSnapshotPolicy + VersioningService 共 9 處 call site 改走 `ArticleFacade`
2. **VersioningService 同時清掉 `ArticleEventPublisher` 跨模組 inject**：把 article event 發送邏輯封裝進 `ArticleFacade.applyRestoreContent` 的 atomic flow
3. **抽取 `SecurityUtils.isAdmin`**：消除 VersionController + SeriesController 兩個重複 private helper
4. **補修 HighlightService 的 articleId null-check**：pre-existing bug（SP-B final review 發現）

**範圍邊界（不在 SP-D 做）:**
- ❌ **TagFacade.deleteArticleTags 改 event**（roadmap §5.3 line 191 假設錯）— 詳見 §10
- ❌ **ArticleQueryService 跨模組 inject**（BookmarkController + SeriesService）— 屬 SP-X
- ❌ **ArticleService 內部結構縮減 / god class 拆分** — 屬 SP-C
- ❌ **HighlightController 等 controller 層修補**（null-check 修在 service 層即足夠 — controller 層拋的會由 service throw 後 GlobalExceptionHandler 捕獲）

**Done definition:**
1. 全 codebase grep `private final ArticleRepository` 在 **`blog-module-version/`** 為 0（其他模組原本就沒有）
2. 全 codebase grep `private final ArticleEventPublisher` 在 **`blog-module-version/`** 為 0（順帶清掉）
3. 全 codebase grep `private boolean isAdmin` **任何模組** 為 0（VersionController + SeriesController）
4. HighlightService.create() / getByArticle() 對 null articleId 一律 throw `BusinessException(ARTICLE_NOT_FOUND)`，不再撞 FK constraint
5. 8 個 affected modules tests 全綠：common / infrastructure / article / version / reading / series / comment / file（含跨模組 IT）

---

## 2. 設計決策摘要

| 決策點 | 選擇 | 理由 |
|------|------|------|
| VersioningService.restore 的 article mutation 怎麼搬 | **ArticleFacade.applyRestoreContent atomic method**（caller 不再看到 Article entity） | 與 SP-B「跨模組 mutation 經 facade」原則一致；Article 模組為 mutation 的 master |
| 是否新增 ArticleContentData DTO | **是** | 既有 `ArticleData` 6 欄位不夠（無 content / title / slug 等），stash 流程必須 |
| ArticleFacade.applyRestoreContent 是否內部接管 syncArticleTags + publish events | **是**（atomic） | 順序敏感（events 必須在 tag sync 之後）；event publishing 是 article 模組 concern |
| TagFacade event 化是否做 | **否（roadmap 假設錯）** | `deleteArticleTags` 唯一 caller 是 `ArticleServiceImpl.update()` 的同步業務需求，不是 article delete flow（FK CASCADE 自動清 article_tags） |
| SecurityUtils.isAdmin 是否提供無參版 | **是（兩 overload）** | 對齊既有 `resolveRole(Authentication)` 風格；無參版讓 controller 簡潔，帶參版利於 unit test |
| HighlightController 是否也補 null-check | **否** | null-check 應在 service layer（articleId 被 resolve 處）；service throw 後 GlobalExceptionHandler 處理，controller 不需要重複 |

---

## 3. 跨模組現況盤點（audit findings）

來源：SP-D brainstorm explorer agent 完整 audit + 補強 grep。

### 3.1 VersioningService 對 ArticleRepository 的 8 處 call

| Line | Method | 用途 | Article 欄位用法 |
|---|---|---|---|
| L57 | `recordAutoSnapshot` | stash to version | authorId + 全內容（snapshotFromArticle） |
| L78 | `recordManualSnapshot` | stash to version | 全內容 |
| L94 | `freezePublished` | stash to version | 全內容 |
| L194 | `restore` | stash + apply | 全內容 |
| **L214** | `restore` | **save mutated article** | **全內容 mutation** |
| L252 | `listByArticle` | 權限驗證 | authorId + id |
| L300 | `findArticleIdByUuidOrThrow` | 權限驗證 + return id | authorId + id |
| L325 | `assertVersionBelongsToArticle` | 比對 id | id |

**分類：**
- **需 ArticleContentData**（4 處）：L57, L78, L94, L194 — 都用 `snapshotFromArticle`
- **既有 ArticleData 已夠**（3 處）：L252, L300, L325 — 權限驗證 + id 比對
- **save**（1 處）：L214 — 透過新 `applyRestoreContent` atomic method 替代

### 3.2 AutoSnapshotPolicy 對 ArticleRepository 的 1 處 call

```java
// L32
Article article = articleRepo.findById(articleId).orElse(null);
// 用 article.getAuthorId() (L35) + article.getContent() length (L46)
```

需 `ArticleContentData`（content 欄位）。

### 3.3 ArticleEventPublisher 跨模組 inject

VersioningService 額外 inject `ArticleEventPublisher`（位於 `blog-module-article/.../service/`），於 `restore()` L225-228 呼叫：

```java
articleEventPublisher.publishContentChanged(saved, Action.RESTORED);
if (saved.getStatus() == ArticleStatus.PUBLISHED) {
    articleEventPublisher.publishUpdated(saved);
}
```

簽名要求 Article entity，跨模組依賴。SP-D 將透過 `ArticleFacade.applyRestoreContent` 內部接管，順帶消除這個 inject。

### 3.4 isAdmin 重複 helper

```java
// VersionController.java L161-164 / SeriesController.java L128-131 — 完全相同實作
private boolean isAdmin() {
    return SecurityContextHolder.getContext().getAuthentication().getAuthorities()
            .contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
}
```

`blog-common/util/SecurityUtils.java` 已存在（含 `resolveRole(Authentication)` static method），但無 `isAdmin`。

### 3.5 HighlightService null-check 缺失

```java
// HighlightService.java L33 (create) + L50 (getByArticle)
Long articleId = articleFacade.findIdByUuid(articleUuid);
// 若 article 不存在 → articleId = null → repo.save(...) 撞 FK constraint
```

對照 `ArticleLikeController.resolveArticleId()` L65-72 已正確 throw `ArticleErrorCode.ARTICLE_NOT_FOUND`。

### 3.6 SeriesFacade.notifyArticleDeletedFromSeries（確認項）

✅ SP-A 已完全清除。`grep "notifyArticleDeletedFromSeries"` 在 main code 為 0，僅 `ArticleServiceImpl.java:303` 有 SP-A 移除註解（合法歷史記錄）。**無 task，純 spec 文件記錄**。

---

## 4. ArticleFacade 新增 method 與 DTO

### 4.1 新 DTO（在 `blog-infrastructure/.../facade/dto/`）

```java
/**
 * 跨模組 article content 完整內容 DTO（給 stash 流程 + content length 比較用）。
 *
 * <p>比 ArticleData 多含 title / slug / content / summary / coverImageUrl 5 個欄位，
 * 適用 VersioningService.snapshotFromArticle / AutoSnapshotPolicy.shouldSnapshot 等需要
 * 「完整 article 內容快照」的場景。</p>
 */
public record ArticleContentData(
    Long id,
    UUID uuid,
    Long authorId,
    String title,
    String slug,
    String content,
    String summary,
    String coverImageUrl,
    String status   // String 對齊 ArticleData，避免 cross-module 強制 import enum
) {}

/**
 * 跨模組 article restore mutation DTO（給 VersioningService.restore 用）。
 *
 * <p>包含 caller (VersioningService) 從 ArticleVersion 取出 + markdown 渲染後
 * 要寫回 article 的全部欄位。tags 一併傳，避免 caller 再 syncArticleTags。</p>
 */
public record ArticleRestoreData(
    String title,
    String slug,
    String content,
    String summary,
    String coverImageUrl,
    String status,                  // 對齊 ArticleStatus.name()
    String contentHtml,             // markdownRenderer.render(content) 由 caller 算好
    java.util.List<UUID> tags       // syncArticleTags 用
) {}
```

### 4.2 ArticleFacade interface 加 2 個 method

```java
// ─── SP-D 新增 1 read ───

/**
 * DB id → ArticleContentData（給 VersioningService stash 流程 + AutoSnapshotPolicy 用）。
 *
 * <p>比 findById 多含 title / slug / content / summary / coverImageUrl 等欄位。</p>
 *
 * @param articleId 文章資料庫主鍵
 * @return ArticleContentData Optional
 */
Optional<ArticleContentData> findContentById(Long articleId);

// ─── SP-D 新增 1 write（atomic restore）───

/**
 * 還原 article 內容到指定版本（atomic）。
 *
 * <p>內部完整流程：</p>
 * <ol>
 *   <li>撈 Article entity</li>
 *   <li>mutate 7 個欄位（title / slug / content / summary / coverImageUrl / status / contentHtml）</li>
 *   <li>save Article</li>
 *   <li>syncArticleTags（由 facade 內部 inject 的 TagFacade 處理）— 必須在 publish events 之前</li>
 *   <li>publishContentChanged(article, RESTORED)</li>
 *   <li>若 article.status == PUBLISHED：publishUpdated(article)</li>
 * </ol>
 *
 * <p>caller 不需要再 inject ArticleEventPublisher / TagFacade write methods，
 * 也不會看到 Article entity。</p>
 *
 * @param articleId 文章資料庫主鍵
 * @param data      還原所需資料（含 tags）
 * @throws BusinessException ARTICLE_NOT_FOUND 若 articleId 對應 article 不存在
 */
void applyRestoreContent(Long articleId, ArticleRestoreData data);
```

### 4.3 為何 atomic 內部接管 publish events + tag sync

1. **順序敏感**：原本 `save → syncArticleTags → publish events`。若 caller 先 `applyRestoreContent` 再 `syncArticleTags`，publish events 會先發生 → search index update 拿到 stale tags
2. **封裝原則**：「哪些 event 該發 / Action 是哪一個」是 article 模組 concern，version 模組不該知道
3. **副作用紅利**：VersioningService 不再需要 inject `ArticleEventPublisher`，順帶消除一個跨模組 Service inject

### 4.4 為何 ArticleFacade 持續擴張的 method 數可接受

SP-B 將 ArticleFacade 從 6 個 method 擴到 16 個，SP-D 再加 2 個變 18 個。雖然數量增加，但：

- 每個 method 對應一個明確的「跨模組需求」，無 over-engineering
- ArticleFacadeImpl 純 delegate（除 atomic 場景）— 維護成本低
- 不分裂出 ArticleQueryFacade / ArticleCommandFacade — SP-X 範圍內再評估

---

## 5. ArticleFacadeImpl — applyRestoreContent atomic 實作要點

```java
@Override
@Transactional
public void applyRestoreContent(Long articleId, ArticleRestoreData data) {
    Article article = articleRepository.findById(articleId)
        .orElseThrow(() -> new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND));

    // 1. mutate 7 欄位
    article.setTitle(data.title());
    article.setSlug(data.slug());
    article.setContent(data.content());
    article.setSummary(data.summary());
    article.setCoverImageUrl(data.coverImageUrl());
    if (data.status() != null) {
        article.setStatus(ArticleStatus.valueOf(data.status()));
    }
    article.setContentHtml(data.contentHtml());

    // 2. save
    Article saved = articleRepository.save(article);

    // 3. syncArticleTags — 必須在 publish events 之前
    tagFacade.syncArticleTags(saved.getUuid(),
        data.tags() != null ? data.tags() : List.of());

    // 4. publish events
    articleEventPublisher.publishContentChanged(saved, Action.RESTORED);
    if (saved.getStatus() == ArticleStatus.PUBLISHED) {
        articleEventPublisher.publishUpdated(saved);
    }
}
```

**注意：**
- ArticleFacadeImpl 已 inject `ArticleService`（SP-B 留下），需新加 inject `ArticleRepository`、`TagFacade`、`ArticleEventPublisher`、`ArticleMarkdownRenderer`（**非也，markdownRenderer 由 caller 算 contentHtml 傳入，facade 內不需**）
- 實際 inject 增加：`ArticleRepository` + `TagFacade` + `ArticleEventPublisher`
- 同模組 inject 不違反任何 anti-pattern

### `findContentById` 純 delegate 實作

```java
@Override
public Optional<ArticleContentData> findContentById(Long articleId) {
    return articleRepository.findById(articleId).map(this::toContentData);
}

private ArticleContentData toContentData(Article a) {
    return new ArticleContentData(
        a.getId(), a.getUuid(), a.getAuthorId(),
        a.getTitle(), a.getSlug(), a.getContent(),
        a.getSummary(), a.getCoverImageUrl(),
        a.getStatus() != null ? a.getStatus().name() : null
    );
}
```

---

## 6. 4 件主任務細節

### 6.1 AutoSnapshotPolicy 改 inject ArticleFacade

```java
// Before
private final ArticleRepository articleRepo;

public boolean shouldSnapshot(Long articleId) {
    Article article = articleRepo.findById(articleId).orElse(null);
    if (article == null) return false;
    AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(article.getAuthorId());
    if (!cfg.enabled()) return false;
    /* ... */
    int diff = Math.abs(currentLength(article) - currentLength(last));  // currentLength(Article)
}

// After
private final ArticleFacade articleFacade;

public boolean shouldSnapshot(Long articleId) {
    ArticleContentData article = articleFacade.findContentById(articleId).orElse(null);
    if (article == null) return false;
    AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(article.authorId());
    if (!cfg.enabled()) return false;
    /* ... */
    int diff = Math.abs(article.content().length() - last.getContent().length());
}
```

含一處微 refactor：`currentLength(Article)` overload 不再需要（直接 `record.content().length()`）。

### 6.2 VersioningService 改 inject ArticleFacade（最重）

**移除 inject:**
- `ArticleRepository articleRepo`
- `ArticleEventPublisher articleEventPublisher`
- 移除 `Article` entity import

**加 inject:**
- `ArticleFacade articleFacade`

**8 處 call site 切換:**
- L57 / L78 / L94: `articleRepo.findById(...)` → `articleFacade.findContentById(...)` (return ArticleContentData)
- L194: 同上
- **L214: `articleRepo.save(article)` → `articleFacade.applyRestoreContent(articleId, restoreData)` (atomic — 同時取代 L218 syncArticleTags + L225-228 publish events)**
- L252: `articleRepo.findByUuid(...)` → `articleFacade.findByUuid(...)` (既有 ArticleData 已夠)
- L300 / L325: 同上

**`snapshotFromArticle` rename + 改型別:**
- 既有 `protected ArticleVersion snapshotFromArticle(Article article, String type, String note)` → `protected ArticleVersion snapshotFromContent(ArticleContentData article, String type, String note)`
- method body 內部 `article.getXxx()` 全改 `article.xxx()` (record accessor)

**restore() 重構後 flow:**

```java
@Transactional
public Article restore(UUID versionUuid, Long currentUserId, boolean isAdmin) {
    ArticleVersion v = versionRepo.findByUuid(versionUuid)
        .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_NOT_FOUND));
    if (!isAdmin && !v.getAuthorId().equals(currentUserId)) {
        throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
    }

    ArticleContentData article = articleFacade.findContentById(v.getArticleId())
        .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));

    /* 1. stash 當前 article 狀態為 AUTO snapshot */
    ArticleVersion stash = snapshotFromContent(article, TYPE_AUTO, null);
    versionRepo.save(stash);
    AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(article.authorId());
    versionMapper.retainAuto(article.id(), cfg.retain());

    /* 2. atomic restore — mutate / save / syncArticleTags / publish events 全在 facade 內 */
    ArticleRestoreData restoreData = new ArticleRestoreData(
        v.getTitle(), v.getSlug(), v.getContent(), v.getSummary(),
        v.getCoverImageUrl(), v.getStatus(),
        markdownRenderer.render(v.getContent()),
        v.getTags() != null ? v.getTags() : List.of()
    );
    articleFacade.applyRestoreContent(article.id(), restoreData);

    return null;  // ← 設計時待確認：return type 是否改為 void / ArticleContentData
}
```

**待 plan 階段釐清:** restore() return type 是否改為 void（VersionController 的 restore endpoint 是否實際使用 return value？）— 若 controller 只看 status code，return void 即可。

### 6.3 SecurityUtils.isAdmin 抽取

**SecurityUtils.java 加 2 個 overload:**

```java
/** 從 SecurityContextHolder 直接取 — controller 簡潔呼叫 */
public static boolean isAdmin() {
    return isAdmin(SecurityContextHolder.getContext().getAuthentication());
}

/** 帶參版 — unit test / 其他傳 Authentication 的 caller 用 */
public static boolean isAdmin(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()
            || authentication instanceof AnonymousAuthenticationToken) {
        return false;
    }
    return authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
}
```

**Controller 改動:**
- VersionController 移除 L161-164 `private isAdmin()` + 改 caller 用 `SecurityUtils.isAdmin()`
- SeriesController 移除 L128-131 同上
- 移除多餘 import：`SecurityContextHolder`, `SimpleGrantedAuthority`（若僅 isAdmin 用過）

**新測試 SecurityUtilsTest.isAdmin 至少 6 個 case:**
- `isAdmin()` 無參版 + `isAdmin(authentication)` 帶參版
- 各對應 4 個情境：null / AnonymousAuthenticationToken / USER role / ADMIN role

### 6.4 HighlightService null-check 補

```java
// Before — L33 / L50
Long articleId = articleFacade.findIdByUuid(articleUuid);
// 直接用 articleId

// After
Long articleId = articleFacade.findIdByUuid(articleUuid);
if (articleId == null) {
    throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
}
// 後續使用
```

新增 unit test:
- `create_articleNotFound_throwsArticleNotFound`
- `getByArticle_articleNotFound_throwsArticleNotFound`

---

## 7. 模組依賴變化

### Before SP-D

```
blog-module-version
  ├── ArticleRepository (跨模組 inject — 8 處 call)
  ├── ArticleEventPublisher (跨模組 inject — 2 處 call)
  └── Article entity (cross-module model)

blog-module-version controllers
  └── isAdmin() private helper (重複 with SeriesController)
```

### After SP-D

```
blog-module-version
  └── ArticleFacade (interface in blog-infrastructure)
       ├── findContentById (read)
       ├── findByUuid / findIdByUuid (既有，read)
       └── applyRestoreContent (atomic write — 內部含 mutate / save / syncTags / publish)

VersionController + SeriesController
  └── SecurityUtils.isAdmin() (blog-common)
```

**淨效果:**
- 跨模組 inject 從「Repository + EventPublisher + entity」三層耦合 → 單一 facade interface
- 重複 helper 抽成 SecurityUtils common 函式
- ArticleFacade 從 16 method → 18 method

---

## 8. 為何 TagFacade event 化從 SP-D scope 移除（roadmap §5.3 line 191 假設修正）

Roadmap §5.3 line 191：「TagFacade.deleteArticleTags 改 event：tag 模組訂 ArticleDeletedEvent → 自己清 article_tags（消除 facade write method）」

### 假設與真實狀況對比

| Roadmap 假設 | 真實狀況 |
|---|---|
| Article delete flow 透過 TagFacade.deleteArticleTags 清 article_tags | ❌ Article delete flow **完全不呼叫** TagFacade write methods（FK CASCADE 自動清，見 ArticleServiceImpl.deleteArticle L289-311 註解） |
| `deleteArticleTags` 是 article delete 流程的清理動作 | ❌ 唯一 caller 是 `ArticleServiceImpl.update()` L250（user 把 tagNames 設為空 list 時清掉 tags），同步業務需求 |
| Event 化可消除 facade write method | ❌ 改 event 會破壞 article update 的立即一致性（user 點 update 後刷新 page 看不到 tag 變化），違反 SP-B §4.3 「caller 需立即一致性 → 用 facade write 不 event」原則 |

### 結論

Roadmap §5.3 line 198「Done definition: TagFacade 不再有 write method」**前提錯誤**，無法達成。SP-D 從 scope 移除此項，spec 中明確記錄為「audit gap」 — roadmap 後續批次規劃時可考慮更新。

TagFacade 4 個 write method（`findOrCreateTags` / `syncArticleTags` / `deleteArticleTags` / `findTagIdsByArticleUuid`）在 SP-D 後仍保留，實際 caller 為：

- ArticleServiceImpl create / update（同步業務需求）
- VersioningService restore（已透過 `ArticleFacade.applyRestoreContent` 內部接管）
- VersioningService snapshotFromArticle 用 `findTagIdsByArticleUuid` (read，不影響)

---

## 9. 測試策略

### 9.1 ArticleFacadeImpl 新 method unit tests

- `findContentById_existing_returnsContentData`：撈到時 return Optional.of(content data)
- `findContentById_notFound_returnsEmpty`：撈不到時 return Optional.empty()
- `findContentById_articleStatusNull_statusFieldNull`：null safety
- `applyRestoreContent_articleNotFound_throwsArticleNotFound`：article 不存在
- `applyRestoreContent_publishedArticle_publishesContentChangedAndUpdated`：完整 atomic flow
- `applyRestoreContent_draftArticle_publishesOnlyContentChanged`：DRAFT 不發 publishUpdated
- `applyRestoreContent_invocationOrder_save_then_syncTags_then_publishEvents`：用 `InOrder` verify 順序

### 9.2 既有 跨模組 unit + IT mock 改動

- AutoSnapshotPolicyTest：`@Mock ArticleRepository` → `@Mock ArticleFacade`，stub 改 ArticleContentData
- VersioningServiceTest：同上 + 移除 `@Mock ArticleEventPublisher`（atomic 後 caller 不發），對 `applyRestoreContent` 的 verify 取代既有 publish events verify
- VersioningServiceTest 既有的 stash 測試：stub 改 `articleFacade.findContentById(...)` return ArticleContentData
- CrossModuleVersionIT：確認 restore 流程仍成功觸發 publish + tag sync（行為等價）

### 9.3 SecurityUtilsTest 新增

至少 6 個 cases（參 §6.3）。

### 9.4 HighlightServiceTest 新增

2 個 cases（參 §6.4）。

### 9.5 全模組 sanity

跑 5 個 affected modules + 連動：
- infrastructure / article / version / reading / series
- 預期 tests 全綠（無新功能，純 refactor + 1 個 bug fix）

---

## 10. ArticleFacade 公開範圍策略

### 10.1 ArticleFacadeImpl inject 增量

SP-D 後 ArticleFacadeImpl 加 inject:
- `ArticleRepository`（同模組，OK）
- `TagFacade`（infrastructure，OK）
- `ArticleEventPublisher`（同模組，OK）

無跨模組 anti-pattern。

### 10.2 為何不拆 ArticleFacade 為 read / write 兩個 interface

- 現有 18 個 method 對應 9 個跨模組需求 — 拆兩個 interface 等於 caller 多一個 inject，無實質好處
- ArticleFacade 介面 Javadoc（SP-B 加）已清楚分類三組 method
- 若未來再爆增（例如 SP-C 拆 god class 時連動加 method），可重新評估

---

## 11. Limitations / Future Work

### 11.1 SP-D 後仍存在的跨模組 inject

| Inject | 位置 | 議題 |
|---|---|---|
| `ArticleQueryService` | BookmarkController + SeriesService | SP-X — 是否進 ArticleFacade 或新 ArticleQueryFacade |
| `ArticleService` | （article 模組內部） | OK — 同模組依賴非 anti-pattern |
| `Article` entity | （article 模組內部） | OK — 同模組依賴 |

### 11.2 後續批次預告

- **SP-C（最後）**：ArticleServiceImpl 1018 行 god class 拆分
  - SP-D 完成後 ArticleFacade 是穩定的對外契約，god class 內部如何拆對 caller 不可見
  - SP-C brainstorm 時細化拆分方案（CommandService / QueryService / ViewService 等）

- **SP-X（無時程）**：ArticleQueryService 跨模組議題
  - 處理 BookmarkController + SeriesService 的 cross-module inject
  - 涉及：是否擴 ArticleFacade（多 1-3 個 read summary method）/ 新建 ArticleQueryFacade / 維持現狀
  - 不影響其他 SP，可獨立評估

---

## 12. Implementation Plan 預估（7 tasks）

| # | Task | 依賴 |
|---|---|---|
| T1 | ArticleFacade interface 加 2 method 宣告 + 2 個 DTO | — |
| T2 | ArticleFacadeImpl applyRestoreContent atomic + findContentById + N unit tests (TDD) | T1 |
| T3 | AutoSnapshotPolicy 改 inject ArticleFacade | T1 |
| T4 | VersioningService 改 inject ArticleFacade（含 8 處 call + restore atomic 流程切換 + ServiceTest） | T2 |
| T5 | SecurityUtils.isAdmin + VersionController/SeriesController 改用 + SecurityUtilsTest | — |
| T6 | HighlightService null-check + 2 unit test | — |
| T7 | 全模組 grep verify + sanity test（純驗證，無 commit） | T1-T6 |

預估 6-7 commits（T7 純驗證不產 commit）。對齊 SP-B 10 commits 規模略小。

---

## 13. 風險評估

| Task | 風險 | 備註 |
|---|---|---|
| T1, T3, T5, T6 | 低 | 純機械式修改 |
| T2 | 中 | applyRestoreContent atomic 邏輯需仔細測試（涉及 publish events 順序，用 InOrder verify） |
| T4 | 中 | 8 處 call 切換 + atomic 流程整合，可能有 IT 殘留 mock 要修；既有 `snapshotFromArticle` rename 為 `snapshotFromContent` |

整體屬 roadmap 評估的「低風險」— 所有改動在已建立 ArticleFacade pattern 上延伸，無架構衝突。

---

## 14. 參考

- Roadmap: `docs/superpowers/specs/2026-05-03-architecture-decoupling-roadmap.md` §5.3
- SP-A 設計（Series MQ Decouple）: `docs/superpowers/specs/2026-05-03-sp-a-series-mq-decouple-design.md`
- SP-B 設計（Article Facade Routing）: `docs/superpowers/specs/2026-05-03-sp-b-article-facade-routing-design.md`
- SP-B PR #34（已 merge）：建立 ArticleFacade routing pattern 基礎
