package dowob.xyz.blog.module.search.reindex;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.index.ArticleIndexAdmin;
import dowob.xyz.blog.module.search.index.IndexState;
import dowob.xyz.blog.module.search.index.SwitchOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文章索引的全量重建：建立新的實體索引、分批寫入、原子切換 alias（D1／PERF-01）。
 *
 * <h3>流程</h3>
 * <ol>
 *   <li>以 annotation mapping 建立新的版本化實體索引，並開始記錄重建期間的變動（{@link ReindexJournal}）</li>
 *   <li>以 keyset 分批讀取 PUBLISHED 文章（每批 {@value #BATCH_SIZE} 篇，標籤與作者暱稱每批各一次批次查詢），bulk 寫入新索引</li>
 *   <li>refresh 後補齊重建期間的變動，再於<b>單一</b> {@code _aliases} 請求內把 alias 從舊索引移到新索引（原子切換）</li>
 *   <li>切換後再補齊一次（涵蓋「補齊之後、切換之前」寫進舊索引的變動），結束記錄，刪除舊索引</li>
 * </ol>
 *
 * <p>新索引只寫入 PUBLISHED 文章，切換後不會有幽靈文件——這取代了原本 {@code reindexAll} 的「掃描並清除幽靈」。</p>
 *
 * <h3>失敗語意</h3>
 * <p>切換前任何一步失敗（讀取、寫入、補齊、鎖遺失、逾時、應用程式關閉、切換本身）：結束記錄、刪除新索引、例外往外拋；
 * alias 維持指向舊索引，<b>不會指到半成品</b>。切換後的補齊失敗：alias 已在新索引上，不刪新索引，
 * 以「已切換、請再執行一次」的訊息往外拋。</p>
 *
 * <p><b>保護</b>：DB 回報 0 篇但現行索引仍有文件時不切換（比較可能是讀取異常而非全站真的沒有文章，照切會清空搜尋；
 * 沿用原 {@code reindexAll} 的同一個判準）；單次重建超過 {@link #MAX_DURATION} 中止。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
public class ArticleIndexRebuilder {

    /** 每批讀取與寫入的文章數 */
    public static final int BATCH_SIZE = 200;

    /** 補齊時每次從變動紀錄取出的筆數 */
    public static final int DRAIN_BATCH_SIZE = 200;

    /** 單次重建的最長執行時間（逾時保護；部落格量級正常為秒級） */
    public static final Duration MAX_DURATION = Duration.ofHours(1);

    /** 文章索引管理 */
    private final ArticleIndexAdmin indexAdmin;

    /** 重建期間的變動紀錄 */
    private final ReindexJournal journal;

    /** 文章 facade（分批讀取、回查 DB 現況） */
    private final ArticleFacade articleFacade;

    /** 使用者 facade（作者當下暱稱） */
    private final UserFacade userFacade;

    /** 逾時判斷用的時鐘 */
    private final Clock clock;

    /**
     * 建立重建元件。
     *
     * @param indexAdmin    文章索引管理
     * @param journal       重建期間的變動紀錄
     * @param articleFacade 文章 facade
     * @param userFacade    使用者 facade
     */
    @Autowired
    public ArticleIndexRebuilder(ArticleIndexAdmin indexAdmin, ReindexJournal journal,
                                 ArticleFacade articleFacade, UserFacade userFacade) {
        this(indexAdmin, journal, articleFacade, userFacade, Clock.systemUTC());
    }

    /**
     * 測試用建構子：指定時鐘。
     *
     * @param indexAdmin    文章索引管理
     * @param journal       重建期間的變動紀錄
     * @param articleFacade 文章 facade
     * @param userFacade    使用者 facade
     * @param clock         時鐘
     */
    ArticleIndexRebuilder(ArticleIndexAdmin indexAdmin, ReindexJournal journal, ArticleFacade articleFacade,
                          UserFacade userFacade, Clock clock) {
        this.indexAdmin = indexAdmin;
        this.journal = journal;
        this.articleFacade = articleFacade;
        this.userFacade = userFacade;
        this.clock = clock;
    }

    /**
     * 執行一次全量重建（同步，在呼叫端的執行緒；由 {@link SearchReindexService} 的背景執行緒呼叫）。
     *
     * @param progress 進度回報：每批之後與切換前各呼叫一次；拋例外即中止（切換前）
     * @return 寫入新索引的文章數（不含補齊）
     * @throws RuntimeException 任何失敗；切換前失敗時 alias 維持原狀、新索引已刪除
     */
    public long rebuild(ReindexProgress progress) {
        Instant deadline = clock.instant().plus(MAX_DURATION);
        IndexState before = indexAdmin.inspect();
        String target = indexAdmin.newVersionedIndexName();
        indexAdmin.createIndex(target);
        journal.begin(target);
        long written;
        try {
            written = bulkLoad(target, progress, deadline);
            guardAgainstEmptySnapshot(written, before);
            indexAdmin.refresh(target);
            drainJournal(target);
            progress.onProgress(written);
            checkStillAllowed(deadline);
        } catch (RuntimeException e) {
            endJournalQuietly();
            indexAdmin.deleteIndexQuietly(target);
            throw e;
        }
        switchAliasOrResolve(target, before);
        try {
            drainJournal(target);
        } catch (RuntimeException e) {
            throw new IllegalStateException("alias 已切換至新索引 " + target + "，但切換後的增量補齊失敗（" + e.getMessage()
                    + "）；重建期間的部分變動可能未反映，請再執行一次重建", e);
        } finally {
            endJournalQuietly();
            deleteOldIndices(before);
        }
        log.info("搜尋索引全量重建完成：alias 已切換至 {}，寫入 {} 篇文章", target, written);
        return written;
    }

    /**
     * 原子切換 alias；請求回報失敗時先確認 ES 端的實際結果再處置（審查 M3）。
     *
     * <ul>
     *   <li>其實已套用（alias 只指向新索引）：視為成功，繼續切換後的流程</li>
     *   <li>確實沒有套用：此時才刪除新索引，原例外往外拋（alias 仍指向舊索引）</li>
     *   <li>無法判斷：不刪任何索引（刪錯會連 alias 一起刪掉、讓 ES 自動建出 dynamic mapping 的同名索引），
     *       以「狀態不明、需人工檢查」的訊息往外拋</li>
     * </ul>
     *
     * @param target 新實體索引
     * @param before 切換前的狀態
     */
    private void switchAliasOrResolve(String target, IndexState before) {
        try {
            indexAdmin.switchAlias(target, before);
            return;
        } catch (RuntimeException e) {
            SwitchOutcome outcome = indexAdmin.verifySwitch(target);
            if (outcome == SwitchOutcome.APPLIED) {
                log.warn("alias 切換請求回報失敗，但 ES 端已套用（alias 只指向 {}），視為切換成功：{}", target, e.getMessage());
                return;
            }
            endJournalQuietly();
            if (outcome == SwitchOutcome.NOT_APPLIED) {
                indexAdmin.deleteIndexQuietly(target);
                throw e;
            }
            throw new IllegalStateException("alias 切換結果狀態不明（" + e.getMessage() + "）：新索引 " + target
                    + " 與舊索引都未刪除，需人工檢查 GET _alias/blog_articles，確認 alias 只指向一個索引後再刪除多餘的實體索引", e);
        }
    }

    /**
     * 以 keyset 分批讀取並寫入新索引。
     *
     * @param target   新實體索引
     * @param progress 進度回報
     * @param deadline 逾時時點
     * @return 寫入篇數
     */
    private long bulkLoad(String target, ReindexProgress progress, Instant deadline) {
        long written = 0;
        UUID cursor = null;
        while (true) {
            checkStillAllowed(deadline);
            List<ArticleIndexData> batch = articleFacade.findPublishedForIndexAfter(cursor, BATCH_SIZE);
            if (batch.isEmpty()) {
                return written;
            }
            indexAdmin.bulkIndex(toDocuments(batch), target);
            written += batch.size();
            journal.keepAlive();
            progress.onProgress(written);
            if (batch.size() < BATCH_SIZE) {
                return written;
            }
            cursor = batch.get(batch.size() - 1).articleUuid();
        }
    }

    /**
     * DB 回報 0 篇、現行索引卻仍有文件：不切換。
     *
     * @param written 寫入新索引的篇數
     * @param before  切換前的狀態
     */
    private void guardAgainstEmptySnapshot(long written, IndexState before) {
        if (written > 0 || before.kind() == IndexState.Kind.ABSENT) {
            return;
        }
        long live = indexAdmin.countLive();
        if (live > 0) {
            throw new IllegalStateException("資料庫回報 0 篇已發布文章，但現行索引仍有 " + live
                    + " 份文件；這比較可能是讀取異常而非全站真的沒有文章，為避免清空搜尋，本次不切換");
        }
    }

    /**
     * 切換前的中止條件：執行緒被中斷（應用程式關閉）或超過最長執行時間。
     *
     * @param deadline 逾時時點
     */
    private void checkStillAllowed(Instant deadline) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("重建被中止：執行緒已中斷（應用程式關閉中）");
        }
        if (clock.instant().isAfter(deadline)) {
            throw new IllegalStateException("重建逾時：超過最長執行時間 " + MAX_DURATION);
        }
    }

    /**
     * 把變動紀錄取空並套用到新索引：文章回查 DB 現況，作者以當下暱稱改寫。
     *
     * @param target 新實體索引
     */
    private void drainJournal(String target) {
        for (List<String> ids = journal.drainArticles(DRAIN_BATCH_SIZE); !ids.isEmpty();
             ids = journal.drainArticles(DRAIN_BATCH_SIZE)) {
            applyArticleChanges(ids, target);
        }
        for (List<String> ids = journal.drainAuthors(DRAIN_BATCH_SIZE); !ids.isEmpty();
             ids = journal.drainAuthors(DRAIN_BATCH_SIZE)) {
            applyAuthorChanges(ids, target);
        }
    }

    /**
     * 套用文章變動：仍為 PUBLISHED 的寫入新索引，其餘（下架、退回、刪除、非 UUID 的垃圾值）從新索引移除。
     *
     * @param ids    文章 UUID 字串
     * @param target 新實體索引
     */
    private void applyArticleChanges(List<String> ids, String target) {
        Map<UUID, String> parsed = new LinkedHashMap<>();
        for (String id : ids) {
            UUID uuid = parseUuidOrNull(id);
            if (uuid == null) {
                indexAdmin.deleteDocument(id, target);
            } else {
                parsed.put(uuid, id);
            }
        }
        if (parsed.isEmpty()) {
            return;
        }
        List<ArticleIndexData> published = articleFacade.findPublishedForIndexByUuids(new ArrayList<>(parsed.keySet()));
        indexAdmin.bulkIndex(toDocuments(published), target);
        Set<UUID> stillPublished = published.stream().map(ArticleIndexData::articleUuid).collect(Collectors.toSet());
        parsed.forEach((uuid, id) -> {
            if (!stillPublished.contains(uuid)) {
                indexAdmin.deleteDocument(id, target);
            }
        });
    }

    /**
     * 套用作者變動：以當下暱稱改寫新索引內該作者的文件（查無此人用通用匿名暱稱）。
     *
     * @param ids    作者 UUID 字串
     * @param target 新實體索引
     */
    private void applyAuthorChanges(List<String> ids, String target) {
        List<UUID> authors = ids.stream().map(ArticleIndexRebuilder::parseUuidOrNull).filter(Objects::nonNull).toList();
        if (authors.isEmpty()) {
            return;
        }
        Map<UUID, UserDisplayInfo> infos = userFacade.getDisplayInfoByUuids(authors);
        for (UUID author : authors) {
            UserDisplayInfo info = infos.get(author);
            String nickname = info != null && info.nickname() != null ? info.nickname() : ArticleDocument.ANONYMOUS_NICKNAME;
            indexAdmin.updateAuthorNickname(author, nickname, target);
        }
    }

    /**
     * 刪除切換前 alias 指向的舊實體索引（改版前的實體索引已在切換請求內以 remove_index 刪除）。
     *
     * @param before 切換前的狀態
     */
    private void deleteOldIndices(IndexState before) {
        if (before.kind() == IndexState.Kind.ALIAS) {
            before.aliasTargets().forEach(indexAdmin::deleteIndexQuietly);
        }
    }

    /**
     * 結束變動紀錄；失敗只記 warn（標記有 TTL，會自行到期）。
     */
    private void endJournalQuietly() {
        try {
            journal.end();
        } catch (RuntimeException e) {
            log.warn("結束搜尋索引重建的變動紀錄失敗（標記將於 TTL 到期後消失）：{}", e.getMessage());
        }
    }

    /**
     * 將一批索引資料轉成 ES 文件；作者暱稱以一次 {@link UserFacade} 批次查詢取當下值（M3）。
     *
     * @param batch 索引資料
     * @return ES 文件
     */
    private List<ArticleDocument> toDocuments(List<ArticleIndexData> batch) {
        if (batch.isEmpty()) {
            return List.of();
        }
        Set<UUID> authorUuids = batch.stream().map(ArticleIndexData::authorUuid).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, UserDisplayInfo> authors = authorUuids.isEmpty() ? Map.of() : userFacade.getDisplayInfoByUuids(authorUuids);
        return batch.stream().map(data -> toDocument(data, authors)).toList();
    }

    /**
     * 將 ArticleIndexData 轉換為 ArticleDocument。
     *
     * <p>author 只存公開 UUID 與暱稱（不含 Long 主鍵與 username）；頂層 {@code uuid} 供幽靈校正排序；status 恆為 PUBLISHED。</p>
     *
     * @param data    文章索引資料
     * @param authors 作者當下顯示資訊
     * @return ES 文件
     */
    private ArticleDocument toDocument(ArticleIndexData data, Map<UUID, UserDisplayInfo> authors) {
        List<ArticleDocument.TagInfo> tags = data.tags() == null ? List.of() : data.tags().stream()
                .map(t -> new ArticleDocument.TagInfo(t.id(), t.name(), t.slug()))
                .toList();
        return ArticleDocument.builder()
                .id(data.articleUuid().toString())
                .uuid(data.articleUuid().toString())
                .title(data.title())
                .summary(data.summary())
                .content(data.contentText())
                .slug(data.slug())
                .author(new ArticleDocument.AuthorInfo(
                        data.authorUuid() == null ? null : data.authorUuid().toString(),
                        currentNickname(data, authors)))
                .tags(tags)
                .publishedAt(data.publishedAt())
                .status("PUBLISHED")
                .build();
    }

    /**
     * 作者暱稱：有 authorUuid 時以當下值為準，查無此人用通用匿名暱稱（不回退到快照的舊暱稱）；沒有 authorUuid 才沿用快照值。
     *
     * @param data    文章索引資料
     * @param authors 作者當下顯示資訊
     * @return 暱稱
     */
    private static String currentNickname(ArticleIndexData data, Map<UUID, UserDisplayInfo> authors) {
        if (data.authorUuid() == null) {
            return data.authorNickname();
        }
        UserDisplayInfo info = authors.get(data.authorUuid());
        return info != null && info.nickname() != null ? info.nickname() : ArticleDocument.ANONYMOUS_NICKNAME;
    }

    /**
     * 寬鬆解析 UUID。
     *
     * @param value 字串
     * @return UUID；格式不符時 null
     */
    private static UUID parseUuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
