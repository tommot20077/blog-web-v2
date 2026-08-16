package dowob.xyz.blog.module.search.service;

import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.model.dto.response.SearchIndexStatusResponse;
import dowob.xyz.blog.module.search.model.dto.response.SearchResultResponse;

import java.util.List;

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
     * 可選 tag 過濾，支援 relevance/latest/hot 排序。
     * 同時記錄搜尋關鍵字至 Redis 熱門搜尋榜與使用者搜尋歷史。
     * </p>
     *
     * @param q       搜尋關鍵字
     * @param tag     標籤 slug 過濾（null 表示不過濾）
     * @param sort    排序方式：{@code relevance}（預設）/ {@code latest} / {@code hot}
     * @param page    頁碼（從 1 開始）
     * @param size    每頁筆數
     * @param userId  當前用戶 ID（匿名為 null，用於記錄搜尋歷史）
     * @return 分頁搜尋結果
     */
    PageResult<SearchResultResponse> search(String q, String tag, String sort, int page, int size, Long userId);

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
     * 從 Redis List {@code search:history:{userId}} 取得最近 20 筆。
     * </p>
     *
     * @param userId 用戶資料庫主鍵
     * @return 搜尋歷史列表（最新在前）
     */
    List<String> getHistory(Long userId);

    /**
     * 清除個人搜尋歷史
     *
     * <p>
     * 刪除 Redis List {@code search:history:{userId}}。
     * </p>
     *
     * @param userId 用戶資料庫主鍵
     */
    void clearHistory(Long userId);

    /**
     * 索引單篇文章
     *
     * <p>
     * 將 ArticleDocument 儲存至 Elasticsearch。
     * 供 MQ 消費者在文章發布時呼叫。
     * </p>
     *
     * @param document 文章 ES document
     */
    void indexArticle(ArticleDocument document);

    /**
     * 從索引中移除文章
     *
     * <p>
     * 供文章狀態變更（下架/封存）或刪除時呼叫。
     * </p>
     *
     * @param articleUuid 文章 UUID 字串
     */
    void deleteIndex(String articleUuid);

    /**
     * 全量重建 Elasticsearch 索引
     *
     * <p>
     * 透過 ArticleFacade 取得所有已發布文章後，批次儲存至 ES。
     * 由 Admin API 手動觸發。
     * </p>
     */
    void reindexAll();

    /**
     * 查詢搜尋索引狀態
     *
     * <p>
     * 提供 Admin 後台查看 Elasticsearch 索引運維概況：文件數、最後一次全量重建時間、
     * ES 是否可達。查詢 ES 失敗時捕捉例外，回傳 {@code healthy=false} 且
     * {@code documentCount=null}，本方法本身不拋出例外，避免拖垮儀表板整格顯示。
     * </p>
     *
     * @return 搜尋索引狀態
     */
    SearchIndexStatusResponse getIndexStatus();
}
