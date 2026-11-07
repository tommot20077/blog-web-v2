package dowob.xyz.blog.module.search.init;

import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.index.ArticleIndexAdmin;
import dowob.xyz.blog.module.search.index.IndexState;
import dowob.xyz.blog.module.search.index.SwitchOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Search index 啟動初始化器：確保 alias {@code blog_articles} 指向一個 mapping 正確的實體索引（D1／PERF-01）。
 *
 * <p>
 * 因 {@link ArticleDocument} 標註 {@code @Document(createIndex = false)}，Spring Data ES
 * 不會在 context 啟動時自動建立索引；若名稱不存在，第一次寫入會由 ES dynamic mapping
 * 建立 — 結果與 Java {@code @Field} annotation 不一致 (例如 {@code status} 變成 text 而非
 * keyword)，導致 {@code term} query 失效、全文搜尋整體 0 result。
 * </p>
 *
 * <h3>三種起點</h3>
 * <ol>
 *   <li><b>全新環境</b>（名稱不存在）：建立 {@value ArticleIndexAdmin#INITIAL_INDEX}，再建立 alias 指向它。
 *       名稱固定為 v1 是為了多台同時啟動：第二台建立會撞名失敗，確認索引已存在後照樣建立 alias
 *       （指向同一個索引，冪等），不會各建一個、讓 alias 指向兩個索引而使寫入失敗。</li>
 *   <li><b>既有環境</b>（{@code blog_articles} 是改版前的實體索引）：<b>自動遷移</b>——以 annotation mapping 建立新的版本化索引、
 *       ES 伺服器端 {@code _reindex} 複製全部文件，再於<b>同一個</b> {@code _aliases} 請求內刪除舊實體索引並建立同名 alias。
 *       選自動遷移而不是報錯要求人工處理：遷移只動衍生資料、全程伺服器端完成（部落格量級為秒級），開發者與部署都不必多做一步。
 *       任何一步失敗都刪掉新索引、<b>保留原實體索引</b>並記 ERROR（不擋啟動）：搜尋與增量索引在實體索引上照常運作，
 *       管理員執行 {@code POST /api/v1/admin/search/reindex} 時全量重建會以同樣的原子切換完成遷移。</li>
 *   <li><b>正常狀態</b>（alias）：檢查 alias 背後實體索引的 mapping。W6 的「缺頂層 uuid 時 putMapping 增補」作用在實體索引上；
 *       mapping 不一致（status 非 keyword 或 tags 非 nested）時改以同樣的遷移流程換成正確 mapping 的新索引
 *       （原本是刪除後重建、資料清空；現在文件會被複製過去）。</li>
 * </ol>
 *
 * <h3>啟動時序</h3>
 * <p>實作 {@link SmartLifecycle} 而非 {@code ApplicationRunner}：Rabbit 的 listener 容器在 context refresh 內
 * （SmartLifecycle，phase 預設 {@link Integer#MAX_VALUE}，javap 查證 {@code AbstractMessageListenerContainer}
 * 與 {@code RabbitListenerEndpointRegistry}）就開始消費，而 {@code ApplicationRunner} 要等 refresh 完成後才跑，
 * listener 可能搶先把文件寫進尚未就緒的索引，讓 ES 動態把欄位建成 text。本類別的 {@link #PHASE} 低於 Rabbit，
 * 因此先於任何 listener 容器 start。選 SmartLifecycle 而不是 {@code @PostConstruct}／{@code InitializingBean}：
 * phase 讓「早於 listener」成為可斷言的契約，且網路 I/O 不放在 bean 建立階段。</p>
 *
 * <p>ES 不可用時 {@link #start()} 的例外會使 context refresh 失敗，與原 {@code ApplicationRunner} 讓啟動失敗的
 * 行為一致（判斷名稱型態與建立全新索引的例外不吞；遷移與補 mapping 的失敗只記 ERROR，原索引仍可用）。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexInitializer implements SmartLifecycle {

    /**
     * 啟動 phase：低於 Rabbit listener 容器與 {@code RabbitListenerEndpointRegistry}（皆為 {@link Integer#MAX_VALUE}），
     * 使索引初始化先完成，listener 才開始消費。
     */
    public static final int PHASE = Integer.MAX_VALUE - 1000;

    /** 復原方式（putMapping 型別衝突、遷移失敗共用）：全量重建會以 annotation mapping 建新索引並原子切換 alias */
    static final String RECOVERY_HINT = "復原方式：執行 POST /api/v1/admin/search/reindex——以 annotation mapping 建立新的實體索引、"
            + "原子切換 alias 後自動刪除舊索引，不需要手動刪除索引或重啟應用程式";

    /** 文章索引的實體索引與 alias 管理 */
    private final ArticleIndexAdmin indexAdmin;

    /** SmartLifecycle 執行狀態 */
    private volatile boolean running;

    /** 啟動時確保索引與 mapping 就緒；例外原樣往外拋（ES 不可用 → 啟動失敗）。 */
    @Override
    public void start() {
        ensureCorrectMapping();
        running = true;
    }

    /** 停止（本類別沒有背景資源需要釋放）。 */
    @Override
    public void stop() {
        running = false;
    }

    /**
     * 是否已完成啟動初始化。
     *
     * @return 已執行過 {@link #start()} 時為 true
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 啟動 phase。
     *
     * @return {@link #PHASE}
     */
    @Override
    public int getPhase() {
        return PHASE;
    }

    /**
     * 確保 alias {@code blog_articles} 指向一個 mapping 對齊 Java annotation 的實體索引（三種起點見類別說明）。
     *
     * <p>{@code author.uuid} 不屬於「換索引」的判準：沒有任何查詢、排序、aggregation 用到 {@code author.*}，
     * 舊 author mapping 不影響搜尋；遷移由全量重建以 id 覆寫文件完成，這裡偵測到舊 author mapping 只記 warn。</p>
     */
    public void ensureCorrectMapping() {
        IndexState state = indexAdmin.inspect();
        switch (state.kind()) {
            case ABSENT -> createInitialIndex();
            case CONCRETE -> migrateToVersionedIndex(state, ArticleIndexAdmin.ALIAS,
                    "Elasticsearch 'blog_articles' 是改版前的實體索引（讀寫改走 alias）");
            case ALIAS -> verifyAliasTarget(state);
        }
    }

    /**
     * 全新環境：建立 {@value ArticleIndexAdmin#INITIAL_INDEX} 並建立 alias，接著照常檢查 mapping。
     *
     * <p>建立失敗時若索引其實已存在（多台同時啟動，另一台先建好），視為成功繼續；確實不存在才往外拋。</p>
     */
    private void createInitialIndex() {
        String initial = ArticleIndexAdmin.INITIAL_INDEX;
        if (!indexAdmin.indexExists(initial)) {
            try {
                indexAdmin.createIndex(initial);
            } catch (RuntimeException e) {
                if (!indexAdmin.indexExists(initial)) {
                    throw e;
                }
                log.info("Elasticsearch 實體索引 {} 已由其他實例建立，沿用之", initial);
            }
        }
        indexAdmin.switchAlias(initial, IndexState.absent());
        log.info("Elasticsearch alias 'blog_articles' 已建立並指向 {}", initial);
        IndexState created = indexAdmin.inspect();
        if (created.kind() == IndexState.Kind.ALIAS) {
            verifyAliasTarget(created);
        }
    }

    /**
     * 正常狀態：檢查 alias 背後實體索引的 mapping，必要時補欄位或換索引。
     *
     * @param state alias 狀態
     */
    private void verifyAliasTarget(IndexState state) {
        if (state.aliasTargets().size() != 1) {
            log.error("Elasticsearch alias 'blog_articles' 同時指向多個實體索引 {}，經 alias 的寫入會被拒絕；"
                    + "執行 POST /api/v1/admin/search/reindex 會建立新索引並讓 alias 只指向它", state.aliasTargets());
            return;
        }
        String physical = state.aliasTargets().get(0);
        Map<String, Object> mapping = indexAdmin.getMapping(physical);
        warnIfLegacyAuthorMapping(physical, mapping);
        if (isMappingValid(mapping)) {
            putTopLevelUuidMappingIfMissing(physical, mapping);
            log.debug("Elasticsearch alias 'blog_articles' → {} mapping 已對齊", physical);
            return;
        }
        migrateToVersionedIndex(state, physical,
                "Elasticsearch 實體索引 " + physical + " 的 mapping 與 Java annotation 不一致（status 非 keyword 或 tags 非 nested）");
    }

    /**
     * 以 annotation mapping 建立新的版本化索引、複製既有文件，再原子切換 alias；失敗時保留原索引並記 ERROR。
     *
     * @param state  切換前的狀態
     * @param source 文件來源（實體 {@code blog_articles} 或 alias 背後的實體索引）
     * @param reason 遷移原因（寫進日誌）
     */
    private void migrateToVersionedIndex(IndexState state, String source, String reason) {
        String target = indexAdmin.newVersionedIndexName();
        log.warn("{}；遷移至新的實體索引 {}（複製既有文件後原子切換 alias）", reason, target);
        long copied;
        try {
            indexAdmin.createIndex(target);
            copied = indexAdmin.copyDocuments(source, target);
            indexAdmin.switchAlias(target, state);
        } catch (RuntimeException e) {
            if (!resolveFailedMigration(state, source, target, e)) {
                return;
            }
            copied = -1;
        }
        if (state.kind() == IndexState.Kind.ALIAS) {
            indexAdmin.deleteIndexQuietly(source);
        }
        log.info("Elasticsearch alias 'blog_articles' 已遷移至 {}，複製 {} 份文件", target, copied < 0 ? "?" : copied);
    }

    /**
     * 遷移的某一步失敗後，<b>先</b>確認 alias 的實際狀態，再決定刪不刪新索引（審查 M3）。
     *
     * <p>切換請求可能回報失敗但 ES 端已套用；從實體索引遷移時舊索引已在同一個請求內被 remove_index，
     * 這時刪掉新索引等於連 alias 與全部資料一起刪掉。</p>
     *
     * @param state  遷移前的狀態
     * @param source 文件來源
     * @param target 本實例建立的新索引
     * @param cause  失敗原因
     * @return 切換其實已套用（呼叫端繼續完成遷移）時 true；其餘情況已處置完畢，回 false
     */
    private boolean resolveFailedMigration(IndexState state, String source, String target, RuntimeException cause) {
        SwitchOutcome outcome = indexAdmin.verifySwitch(target);
        switch (outcome) {
            case APPLIED -> {
                log.warn("Elasticsearch alias 切換請求回報失敗，但 ES 端已套用（alias 只指向 {}），視為遷移成功：{}",
                        target, cause.getMessage());
                return true;
            }
            case UNKNOWN -> {
                log.error("Elasticsearch 索引遷移結果狀態不明（{}）：新索引 {} 與原索引 {} 都未刪除，需人工檢查 GET _alias/blog_articles，"
                        + "確認 alias 只指向一個索引後再刪除多餘的實體索引", cause.getMessage(), target, source, cause);
                return false;
            }
            default -> {
                boolean anotherInstance = migratedByAnotherInstance(state);
                indexAdmin.deleteIndexQuietly(target);
                if (anotherInstance) {
                    log.info("Elasticsearch alias 'blog_articles' 已由其他實例完成遷移，捨棄本實例建立的 {}", target);
                } else {
                    log.error("Elasticsearch 索引遷移失敗，維持原索引 {} 不變（搜尋與增量索引仍可運作）。{}。原因：{}",
                            source, RECOVERY_HINT, cause.getMessage(), cause);
                }
                return false;
            }
        }
    }

    /**
     * 遷移失敗後確認是否其實是被其他實例搶先完成（多台同時啟動）。
     *
     * @param before 本實例遷移前看到的狀態
     * @return 現在已是 alias 且指向與先前不同的實體索引時 true
     */
    private boolean migratedByAnotherInstance(IndexState before) {
        try {
            IndexState now = indexAdmin.inspect();
            return now.kind() == IndexState.Kind.ALIAS && !now.equals(before);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 驗證 mapping 的兩個搜尋必要條件：status 為 keyword（term query）、tags 為 nested（Nested Query）。
     *
     * @param mapping 實體索引的 mapping
     * @return 兩者皆成立時 true
     */
    private boolean isMappingValid(Map<String, Object> mapping) {
        if (mapping == null || !(mapping.get("properties") instanceof Map<?, ?> properties)) {
            return false;
        }
        return fieldTypeIs(properties, "status", "keyword") && fieldTypeIs(properties, "tags", "nested");
    }

    /**
     * 判斷頂層欄位的型別。
     *
     * @param properties mapping 的頂層 properties
     * @param field      欄位名稱
     * @param type       期望的型別
     * @return 欄位存在且型別相符時 true
     */
    private static boolean fieldTypeIs(Map<?, ?> properties, String field, String type) {
        return properties.get(field) instanceof Map<?, ?> fieldMapping && type.equals(fieldMapping.get("type"));
    }

    /**
     * 偵測舊 author mapping（沒有 {@code author.uuid} keyword），只記 warn，不刪除也不重建
     *
     * @param physical 實體索引名稱
     * @param mapping  實體索引的 mapping
     */
    private void warnIfLegacyAuthorMapping(String physical, Map<String, Object> mapping) {
        if (mapping == null || !(mapping.get("properties") instanceof Map<?, ?> properties)) {
            return;
        }
        if (!isAuthorUuidKeyword(properties)) {
            log.warn("Elasticsearch 實體索引 {} 的 author mapping 為舊版（缺少 author.uuid keyword），"
                    + "不影響搜尋，索引不會被刪除；需執行 POST /api/v1/admin/search/reindex 以移除舊作者欄位", physical);
        }
    }

    /**
     * alias 背後的實體索引缺頂層 {@code uuid} keyword 時，以 putMapping 增補（只新增欄位，不刪不重建、不影響既有文件）。
     *
     * <p>幽靈校正排程依此欄位做 search_after 排序。若不先補 mapping，ES 會在第一個帶 {@code uuid} 的文件寫入時
     * 動態建成 text，之後就無法排序。putMapping 失敗記 ERROR（含復原方式），不阻擋應用程式啟動。</p>
     *
     * @param physical 實體索引名稱
     * @param mapping  實體索引目前的 mapping
     */
    private void putTopLevelUuidMappingIfMissing(String physical, Map<String, Object> mapping) {
        if (mapping.get("properties") instanceof Map<?, ?> properties && fieldTypeIs(properties, "uuid", "keyword")) {
            return;
        }
        try {
            if (indexAdmin.putMapping(physical)) {
                log.info("Elasticsearch 實體索引 {} 已增補頂層 uuid keyword mapping；"
                        + "舊文件需 POST /api/v1/admin/search/reindex 補上該欄位後才會被幽靈校正掃到", physical);
                return;
            }
            logPutMappingFailure(physical, "putMapping 未被 ES 接受", null);
        } catch (Exception e) {
            logPutMappingFailure(physical, "putMapping 失敗", e);
        }
    }

    /**
     * putMapping 失敗最常見的原因是 uuid 已被動態建成 text（型別衝突，無法原地修正），記 ERROR 並寫明復原方式。
     *
     * @param physical 實體索引名稱
     * @param what     失敗型態
     * @param cause    例外；ES 回 false 時為 null
     */
    private void logPutMappingFailure(String physical, String what, Exception cause) {
        log.error("Elasticsearch 實體索引 {} 增補頂層 uuid keyword mapping 失敗（{}），"
                        + "常見原因是 uuid 已被動態建成 text，幽靈校正排程在修復前會持續 failed。{}。原因：{}",
                physical, what, RECOVERY_HINT, cause == null ? "-" : cause.getMessage());
    }

    /**
     * 檢查 {@code author.uuid} 是否為 keyword 型別
     *
     * @param properties 索引 mapping 的頂層 properties
     * @return {@code author.properties.uuid.type} 為 keyword 時 true
     */
    private boolean isAuthorUuidKeyword(Map<?, ?> properties) {
        if (!(properties.get("author") instanceof Map<?, ?> authorField)) {
            return false;
        }
        if (!(authorField.get("properties") instanceof Map<?, ?> authorProps)) {
            return false;
        }
        return fieldTypeIs(authorProps, "uuid", "keyword");
    }
}
