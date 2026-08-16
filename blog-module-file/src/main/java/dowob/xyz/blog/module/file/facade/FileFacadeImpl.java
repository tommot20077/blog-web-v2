package dowob.xyz.blog.module.file.facade;

import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.module.file.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * FileFacade 實作
 *
 * <p>
 * 提供文章模組跨模組綁定檔案的統一入口，純委派至 {@link FileService#bindToArticle(UUID, List)}，
 * 不在此層重複「完整替換語意」的判斷邏輯（該邏輯由 blog-module-file 內部負責）。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class FileFacadeImpl implements FileFacade {

    /** 檔案服務 */
    private final FileService fileService;

    /**
     * {@inheritDoc}
     */
    @Override
    public void bindFilesToArticle(UUID articleUuid, List<UUID> fileUuids) {
        fileService.bindToArticle(articleUuid, fileUuids);
    }
}
