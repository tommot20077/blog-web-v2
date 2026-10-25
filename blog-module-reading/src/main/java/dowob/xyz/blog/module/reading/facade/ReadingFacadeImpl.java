package dowob.xyz.blog.module.reading.facade;

import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.module.reading.model.dto.response.ProgressResponse;
import dowob.xyz.blog.module.reading.service.ArticleLikeService;
import dowob.xyz.blog.module.reading.service.BookmarkService;
import dowob.xyz.blog.module.reading.service.ReadingProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ReadingFacade 實作
 *
 * <p>
 * 將 BookmarkService 與 ReadingProgressService 的查詢能力暴露給 article 模組，
 * 避免 blog-module-article ↔ blog-module-reading 的循環依賴。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class ReadingFacadeImpl implements ReadingFacade {

    private final BookmarkService bookmarkService;
    private final ReadingProgressService readingProgressService;
    private final ArticleLikeService articleLikeService;

    @Override
    public Set<UUID> batchIsBookmarked(UUID userUuid, List<UUID> articleUuids) {
        return bookmarkService.batchIsBookmarked(userUuid, articleUuids);
    }

    @Override
    public boolean isBookmarked(UUID userUuid, UUID articleUuid) {
        return bookmarkService.isBookmarked(userUuid, articleUuid);
    }

    @Override
    public Map<Long, BigDecimal> batchGetProgress(Long userId, List<Long> articleIds) {
        return readingProgressService.batchGetProgress(userId, articleIds);
    }

    @Override
    public BigDecimal getProgress(Long userId, UUID articleUuid) {
        Optional<ProgressResponse> opt = readingProgressService.get(userId, articleUuid);
        return opt.map(ProgressResponse::getProgress).orElse(null);
    }

    @Override
    public Set<UUID> batchIsLiked(UUID userUuid, List<UUID> articleUuids) {
        return articleLikeService.batchIsLiked(userUuid, articleUuids);
    }

    @Override
    public boolean isLiked(UUID userUuid, UUID articleUuid) {
        return articleLikeService.isLiked(userUuid, articleUuid);
    }
}
