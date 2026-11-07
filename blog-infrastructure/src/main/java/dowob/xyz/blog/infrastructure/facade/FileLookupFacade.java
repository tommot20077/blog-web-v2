package dowob.xyz.blog.infrastructure.facade;

import java.util.UUID;

/**
 * 檔案模組對外的唯讀查詢 Facade（由 file 模組實作）。
 *
 * <p>供其他模組在不依賴 file 模組的前提下查詢檔案屬性。目前只有 user 模組設定頭像 URL 時，
 * 需要確認指向的站內檔案確實是本人上傳的頭像（上傳端點的 {@code usageType} 由 client 指定，
 * 頭像 5MB 上限只對 AVATAR 生效，不能讓其他用途的檔案被當成頭像）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface FileLookupFacade {

    /**
     * 該檔案是否存在、用途為頭像（AVATAR），且上傳者就是指定使用者。
     *
     * @param fileUuid 檔案 UUID；為 null 時回傳 false
     * @param userUuid 使用者公開 UUID；為 null 時回傳 false
     * @return 三項條件皆成立才為 true；檔案不存在、用途不符或上傳者不同皆為 false
     */
    boolean isAvatarOwnedBy(UUID fileUuid, UUID userUuid);
}
