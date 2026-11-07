package dowob.xyz.blog.e2e.support;

import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.index.ArticleIndexAdmin;
import dowob.xyz.blog.module.search.init.SearchIndexInitializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexInformation;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.DeleteQuery;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * E2E 測試間的搜尋索引清理工具。
 *
 * <p>讀寫改走 alias {@code blog_articles}（D1／PERF-01）後，原本的「{@code indexOps.delete()} 再 {@code createWithMapping()}」
 * 不再可用：對 alias 名稱刪除會被 ES 拒絕，而 {@code createWithMapping()} 會建出同名的<b>實體</b>索引、把 alias 架構拆掉。
 * 一般測試只需要清空文件（{@link #clearArticles()}，經 alias 的 delete_by_query，不動索引結構，也沒有「刪除後、重建前被
 * consumer 寫入而自動建出 dynamic mapping 索引」的空窗）；刻意弄壞索引結構的測試以 {@link #resetToFreshAlias()} 還原。</p>
 */
@Component
public class SearchIndexCleaner {

    @Autowired
    private ElasticsearchOperations esOps;

    @Autowired
    private SearchIndexInitializer initializer;

    /**
     * 清空經 alias 可見的全部文章文件並 refresh（alias 與實體索引不變）。
     */
    public void clearArticles() {
        esOps.delete(DeleteQuery.builder(Query.findAll()).build(), ArticleDocument.class);
        esOps.indexOps(ArticleDocument.class).refresh();
    }

    /**
     * 刪除所有 {@code blog_articles*} 實體索引（含改版前的實體 {@code blog_articles}），再由 initializer 建回
     * 全新環境的狀態（{@code blog_articles_v1} ＋ alias）。
     */
    public void resetToFreshAlias() {
        for (IndexInformation info : esOps.indexOps(IndexCoordinates.of(ArticleIndexAdmin.ALIAS))
                .getInformation(IndexCoordinates.of(ArticleIndexAdmin.ALIAS + "*"))) {
            esOps.indexOps(IndexCoordinates.of(info.getName())).delete();
        }
        initializer.ensureCorrectMapping();
    }
}
