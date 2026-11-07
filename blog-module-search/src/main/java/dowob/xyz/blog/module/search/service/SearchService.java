package dowob.xyz.blog.module.search.service;

import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.model.dto.response.SearchIndexStatusResponse;
import dowob.xyz.blog.module.search.model.dto.response.SearchResultResponse;

import java.util.List;
import java.util.UUID;

/**
 * 搜尋服務介面
 *
 * <p>
 * 定義全文檢索、搜尋建議、搜尋歷史與索引管理等核心操作。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface SearchService {

    /**
     * 全文搜尋文章
     *
     * <p>
     * 使用 Elasticsearch BoolQuery，MultiMatch 搜尋 title/summary/content，
     * 可選 tag 過濾，支援 relevance/latest 排序。
     * 同時記錄搜尋關鍵字至 Redis 熱門搜尋榜與使用者搜尋歷史。
     * </p>
     *
     * @param q       搜尋關鍵字
     * @param tag     標籤 slug 過濾（null 表示不過濾）
     * @param sort    排序方式：{@code relevance}（預設）/ {@code latest}（不分大小寫）；其他值（含已移除的 {@code hot}）拋 400（{@code PARAM_VALID_ERROR}）
     * @param page    頁碼（從 1 開始）
     * @param size    每頁筆數
     * @param userUuid 當前用戶 UUID（匿名為 null，用於記錄搜尋歷史）
     * @return 分頁搜尋結果
     */
    PageResult<SearchResultResponse> search(String q, String tag, String sort, int page, int size, UUID userUuid);

    /**
     * 熱門搜尋詞建議
     *
     * <p>
     * 根據前綴從 Redis ZSet {@code search:hot} 中取得熱門搜尋詞，
     * 使用 {@code ZRANGEBYLEX} 實作前綴自動完成。
     * </p>
     *
     * @param prefix 搜尋前綴
     * @return 符合前綴的熱門搜尋詞列表（最多 10 筆）
     */
    List<String> suggest(String prefix);

    /**
     * 取得個人搜尋歷史
     *
     * <p>
     * 從 Redis List {@code search:history:{userUuid}} 取得最近 20 筆。
     * </p>
     *
     * @param userUuid 用戶 UUID
     * @return 搜尋歷史列表（最新在前）
     */
    List<String> getHistory(UUID userUuid);

    /**
     * 清除個人搜尋歷史
     *
     * <p>
     * 刪除 Redis List {@code search:history:{userUuid}}。
     * </p>
     *
     * @param userUuid 用戶 UUID
     */
    void clearHistory(UUID userUuid);

    /**
     * 索引單篇文章
     *
     * <p>
     * 將 ArticleDocument 經 alias 儲存至 Elasticsearch。
     * 供 MQ 消費者在文章發布時呼叫。全量重建進行中時，寫入前先記進重建變動紀錄（供切換前後補進新索引）；
     * 記錄失敗時例外往外拋、不寫入（由 MQ 容器重試）。
     * </p>
     *
     * @param document 文章 ES document
     */
    void indexArticle(ArticleDocument document);

    /**
     * 從索引中移除文章
     *
     * <p>
     * 供文章狀態變更（下架/封存）或刪除時呼叫。全量重建進行中時，刪除前先記進重建變動紀錄。
     * </p>
     *
     * @param articleUuid 文章 UUID 字串
     */
    void deleteIndex(String articleUuid);

    /**
     * 查詢搜尋索引狀態
     *
     * <p>
     * 提供 Admin 後台查看 Elasticsearch 索引運維概況：文件數、最後一次全量重建時間、
     * ES 是否可達，以及最近一次全量重建作業（讀取失敗時為 {@code null}）。查詢 ES 失敗時捕捉例外，回傳 {@code healthy=false} 且
     * {@code documentCount=null}，本方法本身不拋出例外，避免拖垮儀表板整格顯示。
     * </p>
     *
     * @return 搜尋索引狀態
     */
    SearchIndexStatusResponse getIndexStatus();

    /**
     * 把某位作者所有索引文件的 {@code author.nickname} 改寫為指定暱稱（DATA-02：作者刪帳匿名化後同步索引內的冗餘暱稱）。
     *
     * <p>以 {@code update_by_query}（條件為 {@code author.uuid}）只改寫該欄位，不需要取回文章內容；
     * 該作者沒有任何索引文件時回傳 0。改寫是「設定為某值」，重複執行結果相同（冪等）。
     * 版本衝突（與同時進行的 reindex 競爭）不中止，繼續處理其餘文件。</p>
     *
     * @param authorUuid 作者公開 UUID
     * @param nickname   新的作者暱稱（匿名暱稱）
     * @return 被更新的文件數
     */
    long updateAuthorNickname(UUID authorUuid, String nickname);
}
