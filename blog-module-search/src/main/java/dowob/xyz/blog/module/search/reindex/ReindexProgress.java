package dowob.xyz.blog.module.search.reindex;

/**
 * 全量重建的進度回報（由背景作業提供給 {@link ArticleIndexRebuilder}）。
 *
 * <p>每寫完一批、以及切換 alias 之前各呼叫一次。實作可以拋例外中止重建（例如分散式鎖已遺失），
 * 重建會在切換前停下並清掉新索引。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@FunctionalInterface
public interface ReindexProgress {

    /**
     * 回報目前已寫入新索引的文章數。
     *
     * @param processed 已寫入篇數
     */
    void onProgress(long processed);
}
