package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.infrastructure.facade.FileFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文章「檔案 → 文章」綁定回填元件。
 *
 * <p>
 * 從 {@link ArticleCommandSubService}（Task B6）抽出，供任何「會改動文章 content」的路徑共用，
 * 目前已知呼叫端：{@link ArticleCommandSubService#createArticle} / {@link ArticleCommandSubService#updateArticle}，
 * 以及 {@code ArticleFacadeImpl#applyRestoreContent}（版本還原路徑）。
 * </p>
 *
 * <p>
 * <strong>為什麼每個改動 content 的路徑都必須呼叫本元件</strong>：檔案存取控制採
 * fail-safe 設計——檔案未綁定至任何文章視為私有，僅上傳者與 ADMIN 可讀。
 * 若某條會改變 content 的路徑遺漏掃描回填，會出現兩種問題：
 * (1) 內文新引用的圖始終停在未綁定 = 私有，文章發布後讀者看到破圖；
 * (2) 內文不再引用、但仍停留在舊綁定的圖，會殘留權限（本應解除卻沒解除）。
 * 因此「內文引用的圖 == 已綁定的圖」是本 feature 的不變量，任何 content 變更路徑都要維持。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleFileBinder {

    /** 檔案模組 Facade，供文章儲存後掃描 content 回填「檔案 → 文章」綁定（Task B6） */
    private final FileFacade fileFacade;

    /**
     * 內文檔案連結格式：{@code /api/v1/files/{uuid}/content}（見 spec §3.1 相對路徑決策）。
     * 只匹配標準 UUID 格式（8-4-4-4-12 hex），格式不符者天然不會被擷取，等同安靜略過。
     */
    private static final Pattern FILE_CONTENT_URL_PATTERN = Pattern.compile(
            "/api/v1/files/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/content");

    /**
     * 掃描文章內文，回填「檔案 → 文章」綁定（Task B6）。
     *
     * <p>
     * 上傳圖片當下文章可能尚未存在（使用者開新文章、還沒儲存就貼圖），因此僅靠上傳時綁定
     * 不足夠——這類「先貼圖、後存檔」的檔案會永遠停在未綁定狀態，而未綁定 = 私有（fail-safe），
     * 導致文章發布後讀者看不到圖。任何會改動 content 的路徑（建立 / 更新 / 版本還原）都必須
     * 重新掃描 content 並回填完整清單。
     * </p>
     *
     * <p>
     * <strong>穩健性</strong>：內文是使用者輸入，格式錯誤或不存在的 uuid 一律安靜略過，
     * 絕不可讓呼叫端的主流程（文章儲存 / 還原）因此失敗。{@link FileFacade#bindFilesToArticle}
     * 拋出的任何例外皆視為 best-effort 失敗，僅記錄警告，不往外傳播——文章內容已經存好
     * （或已還原），綁定只是附帶動作，不應讓使用者因為這個非核心步驟而遺失剛才的操作結果。
     * </p>
     *
     * <p>
     * <strong>交易邊界</strong>：呼叫端必須在自身 DB 交易已 commit 之後才呼叫本方法，
     * 比照 code-standards「Transaction + MQ 時序」規範——不在交易作用域內對外（跨模組）
     * 呼叫，避免交易未提交卻已產生外部副作用，也避免在對外呼叫期間持有本模組的 DB 連線。
     * </p>
     *
     * @param articleUuid 文章公開 UUID
     * @param content     文章目前（已儲存 / 已還原）的實際 Markdown 內文（可能為 null）
     */
    public void bindFilesToArticleSafely(UUID articleUuid, String content) {
        List<UUID> fileUuids = extractFileUuids(content);
        try {
            fileFacade.bindFilesToArticle(articleUuid, fileUuids);
        } catch (Exception e) {
            log.warn("檔案綁定回填失敗（best-effort，不影響主流程）：articleUuid={}, error={}",
                    articleUuid, e.getMessage(), e);
        }
    }

    /**
     * 從 Markdown 內文中擷取所有 {@code /api/v1/files/{uuid}/content} 連結的 fileUuid。
     *
     * <p>
     * 格式錯誤的 uuid（不符合標準 8-4-4-4-12 hex 格式）不會被正則比對到，等同安靜略過；
     * 重複出現的 uuid 會去重（保留首次出現順序），無圖時回傳空清單。
     * </p>
     *
     * @param content Markdown 內文（可能為 null 或空白）
     * @return 內文中出現的檔案 UUID 清單（已去重，可能為空清單，但不為 null）
     */
    private List<UUID> extractFileUuids(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Set<UUID> fileUuids = new LinkedHashSet<>();
        Matcher matcher = FILE_CONTENT_URL_PATTERN.matcher(content);
        while (matcher.find()) {
            try {
                fileUuids.add(UUID.fromString(matcher.group(1)));
            } catch (IllegalArgumentException e) {
                log.debug("內文含格式錯誤的檔案 UUID，略過：{}", matcher.group(1));
            }
        }
        return new ArrayList<>(fileUuids);
    }
}
