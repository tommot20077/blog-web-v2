package dowob.xyz.blog.infrastructure.facade;

import java.util.List;
import java.util.UUID;

/**
 * 檔案 Facade 介面
 *
 * <p>
 * 跨模組檔案綁定操作的統一入口，由 blog-module-file 提供實作。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface FileFacade {

    /**
     * 將指定檔案綁定至文章（設定該文章的完整檔案清單）
     *
     * <p>
     * <strong>語意為「完整替換」而非「附加」</strong>：呼叫端傳入的 {@code fileUuids}
     * 必須是該文章「當前的完整檔案清單」，而非本次新增的檔案。
     * 原本已綁定 {@code articleUuid}、但不在本次清單內的既有檔案會被解除綁定。
     * </p>
     *
     * <p>
     * <strong>誤用風險</strong>：若呼叫端誤以為這是「附加」語意，只傳入新增的檔案，
     * 會導致文章既有的舊檔案綁定被錯誤解除——已發布文章中原本可公開讀取的圖片，
     * 會被錯誤地打回「未綁定」狀態而變成僅上傳者與 ADMIN 可讀，造成讀者端圖片失效
     * （屬權限殘留反向問題，不可不慎）。
     * </p>
     *
     * @param articleUuid 文章公開 UUID
     * @param fileUuids   應綁定至此文章的檔案 UUID 完整清單；可為空清單或 null（代表解除此文章的所有綁定）
     */
    void bindFilesToArticle(UUID articleUuid, List<UUID> fileUuids);
}
