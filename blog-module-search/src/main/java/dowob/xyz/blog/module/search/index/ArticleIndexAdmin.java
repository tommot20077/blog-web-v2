package dowob.xyz.blog.module.search.index;

import dowob.xyz.blog.module.search.document.ArticleDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.index.AliasAction;
import org.springframework.data.elasticsearch.core.index.AliasActionParameters;
import org.springframework.data.elasticsearch.core.index.AliasActions;
import org.springframework.data.elasticsearch.core.index.AliasData;
import org.springframework.data.elasticsearch.core.index.Settings;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.core.reindex.ReindexRequest;
import org.springframework.data.elasticsearch.core.reindex.ReindexResponse;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文章索引的實體索引與 alias 管理（D1／PERF-01）。
 *
 * <p><b>命名規則</b>：讀寫一律透過 alias {@value #ALIAS}（{@link ArticleDocument} 的 {@code indexName}、
 * Repository、搜尋查詢、幽靈校正、more_like_this 都只認這個名稱），背後是版本化實體索引
 * {@value #VERSIONED_PREFIX}{@code {yyyyMMddHHmmssSSS}}；全新環境的第一個實體索引固定為 {@value #INITIAL_INDEX}
 * （名稱固定，多台同時啟動時第二台建立會撞名而不是各建一個，見 {@code SearchIndexInitializer}）。
 * 實體索引名稱只出現在本類別與全量重建流程，其他地方不得寫死。</p>
 *
 * <p>alias 的變更一律走單一 {@code _aliases} 請求（{@link #switchAlias}），ES 保證其中的 actions 原子生效：
 * 任何時刻讀者看到的 alias 都恰好指向一個完整的實體索引。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
public class ArticleIndexAdmin {

    /** 讀寫一律使用的 alias 名稱（與 {@link ArticleDocument} 的 {@code indexName} 相同） */
    public static final String ALIAS = "blog_articles";

    /** 版本化實體索引的名稱前綴 */
    public static final String VERSIONED_PREFIX = ALIAS + "_v";

    /** 全新環境建立的第一個實體索引 */
    public static final String INITIAL_INDEX = VERSIONED_PREFIX + "1";

    /** 版本化索引名稱的時間戳格式（毫秒；同一毫秒內不會有兩次重建，重建由分散式鎖序列化） */
    private static final DateTimeFormatter VERSION_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** ES 操作 */
    private final ElasticsearchOperations operations;

    /** 產生版本化索引名稱用的時鐘 */
    private final Clock clock;

    /**
     * 建立索引管理元件。
     *
     * @param operations ES 操作
     */
    @Autowired
    public ArticleIndexAdmin(ElasticsearchOperations operations) {
        this(operations, Clock.systemDefaultZone());
    }

    /**
     * 測試用建構子：指定時鐘。
     *
     * @param operations ES 操作
     * @param clock      時鐘
     */
    ArticleIndexAdmin(ElasticsearchOperations operations, Clock clock) {
        this.operations = operations;
        this.clock = clock;
    }

    /**
     * 判斷 {@value #ALIAS} 目前的型態。
     *
     * <p>以 {@code GET /blog_articles/_alias} 判斷：名稱是 alias 時 ES 會把它展開成背後的實體索引，回傳中帶著該 alias；
     * 名稱是實體索引時回傳的就是它自己（沒有同名 alias）。</p>
     *
     * @return 狀態
     */
    public IndexState inspect() {
        IndexOperations aliasOps = operations.indexOps(IndexCoordinates.of(ALIAS));
        if (!aliasOps.exists()) {
            return IndexState.absent();
        }
        Map<String, Set<AliasData>> aliasesByIndex = aliasOps.getAliasesForIndex(ALIAS);
        List<String> targets = aliasesByIndex.entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(a -> ALIAS.equals(a.getAlias())))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        return targets.isEmpty() ? IndexState.concrete() : IndexState.alias(targets);
    }

    /**
     * 產生新的版本化實體索引名稱（{@value #VERSIONED_PREFIX} ＋ 毫秒時間戳）。
     *
     * @return 名稱
     */
    public String newVersionedIndexName() {
        return VERSIONED_PREFIX + LocalDateTime.now(clock).format(VERSION_FORMAT);
    }

    /**
     * 實體索引是否存在。
     *
     * @param index 實體索引名稱
     * @return 存在時 true
     */
    public boolean indexExists(String index) {
        return operations.indexOps(IndexCoordinates.of(index)).exists();
    }

    /**
     * 以 {@link ArticleDocument} 的 annotation settings 與 mapping 建立實體索引（與原 {@code createWithMapping} 相同的定義）。
     *
     * @param index 實體索引名稱
     */
    public void createIndex(String index) {
        IndexOperations entityOps = operations.indexOps(ArticleDocument.class);
        Settings settings = entityOps.createSettings(ArticleDocument.class);
        Document mapping = entityOps.createMapping(ArticleDocument.class);
        operations.indexOps(IndexCoordinates.of(index)).create(settings, mapping);
        log.info("已建立文章實體索引 {}", index);
    }

    /**
     * 在<b>單一</b> {@code _aliases} 請求內，讓 {@value #ALIAS} 改為只指向 {@code newIndex}（ES 保證原子生效）。
     *
     * <ul>
     *   <li>先前是 alias：移除它在每個舊實體索引上的指向（舊索引本身不刪，由呼叫端在切換成功後刪除）</li>
     *   <li>先前是實體索引：以 {@code remove_index} 刪除該實體索引——同名的 alias 必須在同一個請求內建立，
     *       否則中間會有一段時間名稱不存在，寫入會被 ES 自動建成 dynamic mapping 的新索引</li>
     *   <li>先前不存在：只新增指向</li>
     * </ul>
     *
     * <p>任何一個 action 失敗（例如舊索引已被別人刪掉），整個請求都不生效，例外往外拋。</p>
     *
     * @param newIndex 新的實體索引
     * @param previous 切換前的狀態（{@link #inspect()} 的結果）
     */
    public void switchAlias(String newIndex, IndexState previous) {
        AliasActions actions = new AliasActions();
        switch (previous.kind()) {
            case ALIAS -> previous.aliasTargets().forEach(old -> actions.add(new AliasAction.Remove(
                    AliasActionParameters.builder().withIndices(old).withAliases(ALIAS).build())));
            case CONCRETE -> actions.add(new AliasAction.RemoveIndex(
                    AliasActionParameters.builder().withIndices(ALIAS).build()));
            case ABSENT -> {
                /* 沒有舊指向需要移除 */
            }
        }
        actions.add(new AliasAction.Add(AliasActionParameters.builder().withIndices(newIndex).withAliases(ALIAS).build()));
        operations.indexOps(IndexCoordinates.of(newIndex)).alias(actions);
        log.info("alias {} 已切換至 {}（切換前：{} {}）", ALIAS, newIndex, previous.kind(), previous.aliasTargets());
    }

    /**
     * 刪除實體索引；失敗（含不存在）只記 warn、不拋例外。
     *
     * <p>用於清理：重建失敗後刪除半成品、切換成功後刪除舊索引。這兩個時點的刪除失敗都不影響讀者
     * （alias 不指向它），留下的索引只佔空間，不該讓已決定的結果變成失敗。</p>
     *
     * @param index 實體索引名稱
     * @return 是否刪除成功
     */
    public boolean deleteIndexQuietly(String index) {
        try {
            boolean deleted = operations.indexOps(IndexCoordinates.of(index)).delete();
            if (!deleted) {
                log.warn("刪除文章實體索引 {} 未生效（索引可能不存在）", index);
            }
            return deleted;
        } catch (RuntimeException e) {
            log.warn("刪除文章實體索引 {} 失敗，需人工清理：{}", index, e.getMessage());
            return false;
        }
    }

    /**
     * 以 ES 伺服器端的 {@code _reindex} 把 {@code source} 的全部文件複製到 {@code target}（等待完成並 refresh）。
     *
     * @param source 來源（實體索引或 alias）
     * @param target 目標實體索引
     * @return 寫入目標的文件數（新建＋覆寫）
     * @throws IllegalStateException 有任何文件複製失敗或逾時
     */
    public long copyDocuments(String source, String target) {
        ReindexResponse response = operations.reindex(
                ReindexRequest.builder(IndexCoordinates.of(source), IndexCoordinates.of(target)).withRefresh(true).build());
        if (!response.getFailures().isEmpty() || response.isTimedOut()) {
            throw new IllegalStateException("複製文件 " + source + " → " + target + " 失敗：failures="
                    + response.getFailures().size() + ", timedOut=" + response.isTimedOut());
        }
        return response.getCreated() + response.getUpdated();
    }

    /**
     * 讀取實體索引的 mapping。
     *
     * @param index 實體索引名稱
     * @return mapping（含 {@code properties}）
     */
    public Map<String, Object> getMapping(String index) {
        return operations.indexOps(IndexCoordinates.of(index)).getMapping();
    }

    /**
     * 以 {@link ArticleDocument} 的 annotation mapping 對實體索引 putMapping（只能新增欄位，不能改既有欄位型別）。
     *
     * @param index 實體索引名稱
     * @return ES 是否接受
     */
    public boolean putMapping(String index) {
        Document mapping = operations.indexOps(ArticleDocument.class).createMapping(ArticleDocument.class);
        return operations.indexOps(IndexCoordinates.of(index)).putMapping(mapping);
    }

    /**
     * refresh 實體索引，使先前寫入的文件可被搜尋。
     *
     * @param index 實體索引名稱
     */
    public void refresh(String index) {
        operations.indexOps(IndexCoordinates.of(index)).refresh();
    }

    /**
     * 以一次 bulk 請求把文件寫入指定實體索引（id 相同者覆寫）；任何一筆失敗時整批以例外回報。
     *
     * @param documents 文件；空清單不發請求
     * @param index     實體索引名稱
     */
    public void bulkIndex(List<ArticleDocument> documents, String index) {
        if (documents.isEmpty()) {
            return;
        }
        operations.save(documents, IndexCoordinates.of(index));
    }

    /**
     * 從指定實體索引刪除一份文件（不存在時為 no-op）。
     *
     * @param id    文件 id（文章 UUID 字串）
     * @param index 實體索引名稱
     */
    public void deleteDocument(String id, String index) {
        operations.delete(id, IndexCoordinates.of(index));
    }

    /**
     * 經 alias 計算線上索引的文件數。
     *
     * @return 文件數
     */
    public long countLive() {
        return operations.count(Query.findAll(), ArticleDocument.class);
    }
}
