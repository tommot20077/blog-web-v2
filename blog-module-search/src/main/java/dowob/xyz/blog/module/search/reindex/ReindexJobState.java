package dowob.xyz.blog.module.search.reindex;

/**
 * 搜尋索引全量重建作業的狀態。
 *
 * @author Yuan
 * @version 1.0
 */
public enum ReindexJobState {

    /** 背景執行中 */
    RUNNING,

    /** 已完成：alias 已切換到新索引 */
    COMPLETED,

    /** 已失敗：切換前失敗時 alias 仍指向舊索引（錯誤摘要見 {@code error}） */
    FAILED
}
