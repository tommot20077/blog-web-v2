package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 收藏列表查詢編排。
 *
 * <p>自 {@code BookmarkController} 抽出（ARCH-09：Controller 不該做跨模組編排與
 * 分頁計算，且內部 {@code Long} 主鍵不該在 web 層流動）。</p>
 *
 * <p><b>為何先過濾再分頁</b>：收藏端點不檢查 status，且文章下架（PUBLISHED → ARCHIVED）
 * 只改狀態、不刪 bookmark 列，因此列表必須自行過濾。若沿用「SQL 分頁 ＋ 應用層過濾」，
 * {@code total} 會高估、每頁筆數不一致——而前端以 {@code pages} 畫分頁器，
 * 高估會產生點得進去的空尾頁。</p>
 *
 * <p>可見性判斷委派 {@code ArticleFacade.filterReadableUuids}（集合述詞，由 owner 模組
 * 套用 {@code ArticleVisibility} 這份單一真相），與文章詳情端點同一套政策：
 * 作者本人與 ADMIN 仍看得到自己收藏的非公開文章。</p>
 *
 * <p><b>跨模組邊界</b>：流程為「收藏的文章 uuid 清單 → {@code filterReadableUuids} → 分頁 →
 * {@code getSummariesByUuids}」，全程只經 {@link ArticleFacade}，不 import article 模組的任何類別。
 * liked／bookmarked／lastReadProgress 與 series 導覽的補值由 facade 的實作負責，回應 JSON 與解耦前相同。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class BookmarkQueryService {

    private final BookmarkService bookmarkService;
    private final ArticleFacade articleFacade;

    /**
     * 我的收藏列表（已濾除對本人不可見的文章）。
     *
     * @param userUuid 當前使用者公開 UUID
     * @param isAdmin  當前使用者是否為 ADMIN
     * @param page     頁碼（自 1 起）
     * @param size     每頁筆數
     * @return 收藏文章摘要分頁；{@code total} 為<b>可見</b>收藏數
     */
    @Transactional(readOnly = true)
    public PageResult<ArticleSummaryResponse> listMyBookmarks(UUID userUuid, boolean isAdmin,
                                                              int page, int size) {
        page = Math.max(page, 1);
        size = Math.max(size, 1);

        List<UUID> allUuids = bookmarkService.findAllMyBookmarkedArticleUuids(userUuid);
        if (allUuids.isEmpty()) {
            return PageResult.of(page, size, 0L, List.of());
        }

        List<UUID> visibleUuids = articleFacade.filterReadableUuids(allUuids, userUuid, isAdmin);
        long total = visibleUuids.size();

        // page 為 client 給的 int，(page - 1) * size 以 int 相乘在大 page 時會溢位成負數，
        // 使負的 offset 繞過下面的範圍守衛而直接炸 subList 的 IndexOutOfBoundsException。
        // (long) 必須放在第一個運算元上才能讓整個乘法以 long 運算，避免相乘當下就已溢位。
        long offset = (long) (page - 1) * size;
        if (offset >= visibleUuids.size()) {
            return PageResult.of(page, size, total, List.of());
        }
        int from = (int) offset;
        List<UUID> pageUuids = visibleUuids.subList(from, Math.min(from + size, visibleUuids.size()));

        return PageResult.of(page, size, total, articleFacade.getSummariesByUuids(pageUuids));
    }
}
