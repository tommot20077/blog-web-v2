package dowob.xyz.blog.module.reading.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 測試用存取器：呼叫 {@link ReadingProgressService} 的 package-private 五參數 {@code update}，
 * 讓其他 package 的整合測試（例如 flush job）也能以固定的版本時間 {@code ts} 重現交錯情境。
 *
 * @author Yuan
 * @version 1.0
 */
public final class ReadingProgressTestAccess {

    private ReadingProgressTestAccess() {
    }

    /**
     * 以指定版本時間更新閱讀進度。
     *
     * @param service     被測 service
     * @param userUuid    讀者公開 UUID
     * @param articleUuid 文章公開 UUID
     * @param progress    進度
     * @param lastHeading 標題錨點（可為 null）
     * @param requestTs   版本時間（epoch ms）
     */
    public static void update(ReadingProgressService service, UUID userUuid, UUID articleUuid,
                              BigDecimal progress, String lastHeading, long requestTs) {
        service.update(userUuid, articleUuid, progress, lastHeading, requestTs);
    }
}
