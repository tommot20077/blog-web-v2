package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;

/**
 * 一次自動快照的執行計畫：{@link AutoSnapshotPolicy} 判斷「該寫快照」時，把已查好的文章內容與生效設定一併交出，
 * 讓 {@link VersioningService#recordAutoSnapshot(AutoSnapshotPlan)} 直接使用，同一次事件處理不再重查（PERF-21）。
 *
 * @param article 判斷當下查到的文章內容（快照即以此內容寫入）
 * @param config  該文章作者生效的自動快照設定（retain 供滾動保留使用）
 * @author Yuan
 * @version 1.0
 */
public record AutoSnapshotPlan(ArticleContentData article, AutoSnapshotConfig config) {
}
