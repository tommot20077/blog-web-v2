package dowob.xyz.blog.module.tag.service;

import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.model.dto.TagDetailResponse;
import dowob.xyz.blog.module.tag.model.dto.UpdateTagRequest;

import java.util.List;
import java.util.UUID;

/**
 * 標籤服務介面
 *
 * <p>
 * 定義標籤查詢、使用者追蹤及管理員操作的業務合約。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface TagService {

    /**
     * 根據前綴返回標籤自動補全建議
     *
     * @param prefix 搜尋前綴
     * @param limit  最大返回數量
     * @return Slug 列表
     */
    List<String> suggest(String prefix, int limit);

    /**
     * 取得熱門標籤列表
     *
     * @param limit 最大返回數量
     * @return 熱門標籤列表
     */
    List<Tag> getHotTags(int limit);

    /**
     * 依 Slug 取得標籤詳情（不帶 user context — followed 永遠 false）
     *
     * @param slug 標籤 Slug
     * @return 標籤詳情回應
     */
    TagDetailResponse getTagDetail(String slug);

    /**
     * 依 Slug 取得標籤詳情，附加當前使用者的追蹤狀態
     *
     * @param slug              標籤 Slug
     * @param currentUserUuid   當前使用者 UUID（可為 null，未認證時即為 null）
     * @return 標籤詳情回應，followed 反映該使用者是否已追蹤此標籤
     */
    TagDetailResponse getTagDetail(String slug, UUID currentUserUuid);

    /**
     * 使用者追蹤標籤
     *
     * @param tagId  標籤 ID
     * @param userId 使用者 ID
     */
    void followTag(UUID tagId, UUID userId);

    /**
     * 使用者取消追蹤標籤
     *
     * @param tagId  標籤 ID
     * @param userId 使用者 ID
     */
    void unfollowTag(UUID tagId, UUID userId);

    /**
     * 管理員更新標籤屬性
     *
     * @param id      標籤 ID
     * @param request 更新請求
     * @return 更新後的標籤實體
     */
    Tag adminUpdateTag(UUID id, UpdateTagRequest request);

    /**
     * 管理員刪除標籤
     *
     * @param id 標籤 ID
     */
    void adminDeleteTag(UUID id);
}
