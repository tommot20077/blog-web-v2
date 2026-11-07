package dowob.xyz.blog.module.search.reindex;

import dowob.xyz.blog.module.search.model.dto.response.SearchReindexJobResponse;

/**
 * 觸發全量重建的結果。
 *
 * @param started 是否由本次呼叫建立了新作業；{@code false} 代表已有作業在跑（呼叫端回 409）
 * @param job     新建立的作業，或進行中的既有作業（取不到狀態時為 {@code null}）
 * @author Yuan
 * @version 1.0
 */
public record ReindexStartResult(boolean started, SearchReindexJobResponse job) {

    /**
     * 新作業已建立並交給背景執行緒。
     *
     * @param job 新作業
     * @return 結果
     */
    public static ReindexStartResult started(SearchReindexJobResponse job) {
        return new ReindexStartResult(true, job);
    }

    /**
     * 已有作業在跑，本次未建立新作業。
     *
     * @param running 進行中的作業；取不到時為 {@code null}
     * @return 結果
     */
    public static ReindexStartResult alreadyRunning(SearchReindexJobResponse running) {
        return new ReindexStartResult(false, running);
    }
}
