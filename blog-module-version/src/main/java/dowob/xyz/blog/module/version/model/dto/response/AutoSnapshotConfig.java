package dowob.xyz.blog.module.version.model.dto.response;

/**
 * 自動快照配置（user effective）— PreferenceResolver 的輸出 record。
 *
 * @param enabled         是否啟用自動快照
 * @param retain          滾動保留份數
 * @param intervalSeconds 自動快照觸發時間間隔（秒）
 * @param diffChars       自動快照觸發字元差距門檻；0 表示 disabled（純時間觸發）
 *
 * @author Yuan
 * @version 1.0
 */
public record AutoSnapshotConfig(
    boolean enabled,
    int retain,
    int intervalSeconds,
    int diffChars
) {}
