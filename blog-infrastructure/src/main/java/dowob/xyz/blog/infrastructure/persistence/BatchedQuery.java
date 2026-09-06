package dowob.xyz.blog.infrastructure.persistence;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Metrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * 無上界輸入的批次查詢原語。
 *
 * <p><b>存在理由</b>：MyBatis {@code <foreach>} 會把集合展開成 {@code IN (?, ?, ...)}，
 * 每個元素佔一個 bind parameter。PostgreSQL 的 extended protocol 上限為 65535 個
 * bind parameter，超過即拋 {@code PSQLException}——不是變慢，是整個請求 500。
 * 且在遠低於該硬上限處，planning time 就會隨列表長度顯著增長。
 * 凡是輸入大小隨資料量成長（而非以頁大小為界）的查詢，都必須經由本類切批。</p>
 *
 * <p><b>適用範圍（重要）</b>：本原語僅適用於<b>跨批可合併</b>的查詢，亦即
 * 「先切批分別查、再串接結果」與「一次查全部」等價者。典型的合法形狀為
 * 集合聯集（filter 類）、Map 合併（key 不重疊的 count 類）。</p>
 *
 * <p><b>不得使用於</b>：帶 {@code ORDER BY} ＋ {@code LIMIT} 的查詢
 * （各批的前 N 名合併後不等於全域前 N 名），或跨批聚合
 * （{@code SUM} / {@code AVG} / {@code DISTINCT} 等需要看到完整輸入才正確者）。
 * 這類查詢切批會<b>靜默給出錯誤答案</b>，比不切批更危險。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class BatchedQuery {

    /**
     * 單批最大筆數。
     *
     * <p>與既有 {@code ArticleFacadeImpl.PUBLISHED_FILTER_BATCH_SIZE} 對齊，
     * 該常數是本專案先前為同一問題選定的安全線；此處統一為單一來源。</p>
     */
    public static final int BATCH_SIZE = 500;

    /**
     * 輸入集合大小的分佈指標名稱。
     *
     * <p>切批解決的是「不會炸」，不是「知道發生什麼」——沒有這個指標，
     * 輸入是 300 筆還是 3,000 筆無從分辨，重評門檻只能靠人記得去看 JavaDoc。
     * 打在本類一處即涵蓋全部呼叫端，是把切批抽成共用原語的附帶紅利。</p>
     *
     * <p>以呼叫端類別為 {@code caller} tag，使分佈偏高時查得出是哪條路徑。
     * 全域 registry 未掛任何子 registry 時（單元測試、未啟用 actuator）整段跳過，
     * 不付出 {@link StackWalker} 的成本。</p>
     */
    public static final String INPUT_SIZE_METRIC = "blog.batched.query.input.size";

    /**
     * 靜態原語類別，不提供實例化。
     */
    private BatchedQuery() {
    }

    /**
     * 將輸入切成安全批次逐批查詢，並依批次順序串接結果。
     *
     * <p>空輸入（{@code null} 或空集合）<b>完全不呼叫</b> {@code query}，
     * 以免產生語法非法的 {@code IN ()}。</p>
     *
     * <p>回傳順序為「批次順序 × 各批內查詢回傳的順序」。若呼叫端需要與輸入一致的順序，
     * 不得依賴本方法的回傳順序（資料庫不保證 {@code IN} 查詢的回傳順序），
     * 須自行依輸入序重排。</p>
     *
     * @param inputs 待查詢的輸入集合；{@code null} 或空集合回傳空清單
     * @param query  單批查詢函式，收到的批次大小不超過 {@link #BATCH_SIZE}
     * @param <I>    輸入元素型別
     * @param <O>    輸出元素型別
     * @return 各批結果依序串接後的清單
     */
    public static <I, O> List<O> queryInBatches(Collection<I> inputs,
                                                Function<List<I>, List<O>> query) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        recordInputSize(inputs.size());
        List<I> materialized = new ArrayList<>(inputs);
        List<O> results = new ArrayList<>();
        for (int from = 0; from < materialized.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, materialized.size());
            results.addAll(query.apply(materialized.subList(from, to)));
        }
        return results;
    }

    /**
     * 記錄本次輸入的總大小。
     *
     * <p>記的是<b>輸入總量</b>而非批次大小——批次大小恆為 {@link #BATCH_SIZE} 或餘數，
     * 沒有資訊量；輸入總量才是重評門檻要看的數字。</p>
     *
     * @param size 本次輸入的元素數
     */
    private static void recordInputSize(int size) {
        if (Metrics.globalRegistry.getRegistries().isEmpty()) {
            return;
        }
        DistributionSummary.builder(INPUT_SIZE_METRIC)
                .description("跨模組批次查詢的輸入集合大小；用於判斷是否逼近切批與 bind parameter 的界限")
                .baseUnit("rows")
                .tag("caller", callerClassName())
                .register(Metrics.globalRegistry)
                .record(size);
    }

    /**
     * 取得呼叫端的類別簡單名稱。
     *
     * <p>跳過本類自身的 frame，取第一個外部呼叫者。使用 {@link StackWalker} 而非
     * 完整 stack trace，成本相對本方法必然伴隨的 DB 查詢可忽略。</p>
     *
     * @return 呼叫端類別簡單名稱；解析不出時回傳 {@code unknown}
     */
    private static String callerClassName() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames
                        .map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(c -> c != BatchedQuery.class)
                        .findFirst()
                        .map(Class::getSimpleName)
                        .orElse("unknown"));
    }
}
