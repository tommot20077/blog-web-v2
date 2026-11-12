package dowob.xyz.blog.module.file.service;

import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 依文章內文回填「檔案 → 文章」綁定（W5A／Q12：由 article 模組的 {@code ArticleFileBinder} 搬入 file 模組）。
 *
 * <p>
 * 由 {@code FileArticleContentChangedConsumer} 在收到 {@code ArticleContentChangedEvent}
 * （SAVED／PUBLISHED／RESTORED）後呼叫。事件只帶 articleUuid，故經 {@link ArticleLookupFacade#findContentByUuid}
 * 取文章<b>目前最新</b>的內文，解析其中的 {@code /api/v1/files/{uuid}/content} 連結，再以
 * {@link FileService#bindToArticle} 完整替換該文章的綁定清單。
 * </p>
 *
 * <p>
 * <strong>不變量</strong>：檔案存取控制採 fail-safe——檔案未綁定至任何文章視為私有，僅上傳者與 ADMIN 可讀。
 * 因此「內文引用的圖 == 已綁定的圖」必須在任何內容變更後（最終）成立：新引用的圖若停在未綁定，
 * 文章發布後讀者會看到破圖；不再引用的圖若仍留著舊綁定，會殘留公開讀取權限。
 * 取「最新內文」而非事件當下的內文，使事件亂序或重送時結果仍收斂到最新狀態（回填本身冪等）。
 * </p>
 *
 * <p>
 * <strong>失敗語意</strong>：與舊的同步版本不同，這裡<b>不吞例外</b>——失敗往外拋，由 consumer 的冪等交易
 * rollback 並交給容器重試（耗盡進 DLQ），不再「記一行警告就算了」。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleFileBindingService {

    /**
     * 內文檔案連結格式：{@code /api/v1/files/{uuid}/content}（見 spec §3.1 相對路徑決策）。
     * 只匹配標準 UUID 格式（8-4-4-4-12 hex），格式不符者天然不會被擷取，等同安靜略過。
     */
    private static final Pattern FILE_CONTENT_URL_PATTERN = Pattern.compile(
            "/api/v1/files/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/content");

    /** 檔案服務（綁定的完整替換語意與擁有權不變量在此） */
    private final FileService fileService;

    /** 文章最小依賴查詢 Facade（取最新內文；不得改注入 ArticleFacade，見其 JavaDoc） */
    private final ArticleLookupFacade articleLookupFacade;

    /**
     * 讀取文章目前最新內文，並以其引用的檔案清單完整替換該文章的檔案綁定。
     *
     * <p>文章已不存在（例如事件到達前已被刪除）時直接略過，不動任何綁定——文章刪除後的檔案去留不在本流程範圍。
     * 內文已無任何圖時以空清單綁定，解除先前殘留的綁定。</p>
     *
     * @param articleUuid 文章公開 UUID；為 null 時略過
     * @return 實際執行了綁定則 true；略過（articleUuid 為 null 或文章不存在）則 false
     */
    public boolean rebindFromArticleContent(UUID articleUuid) {
        if (articleUuid == null) {
            return false;
        }
        Optional<String> content = articleLookupFacade.findContentByUuid(articleUuid);
        if (content.isEmpty()) {
            log.info("文章已不存在，略過檔案綁定回填 articleUuid={}", articleUuid);
            return false;
        }
        fileService.bindToArticle(articleUuid, extractFileUuids(content.get()));
        return true;
    }

    /**
     * 從 Markdown 內文中擷取所有 {@code /api/v1/files/{uuid}/content} 連結的 fileUuid。
     *
     * <p>格式錯誤的 uuid（不符合標準 8-4-4-4-12 hex 格式）不會被正則比對到，等同安靜略過；
     * 重複出現的 uuid 會去重（保留首次出現順序），無圖時回傳空清單。</p>
     *
     * @param content Markdown 內文（可能為 null 或空白）
     * @return 內文中出現的檔案 UUID 清單（已去重，可能為空清單，但不為 null）
     */
    public static List<UUID> extractFileUuids(String content) {
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
