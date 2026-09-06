package dowob.xyz.blog.infrastructure.facade;

import dowob.xyz.blog.infrastructure.facade.dto.SeriesBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesNavigation;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Series 跨模組 read facade。
 *
 * <p>給 ArticleQueryService 等跨模組讀取 series 導覽資訊用。
 * 對齊 ReadingFacade pattern：interface 在 infrastructure，impl 在 owning module。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface SeriesFacade {

    /**
     * 取得文章在 series 中的導覽（prev/next）。
     *
     * @param articleId 文章資料庫主鍵
     * @return 若文章在 series 中回傳導覽；否則 Optional.empty()
     */
    Optional<SeriesNavigation> getSeriesNavigation(Long articleId);

    /**
     * 批次取得多個 series 的基本資訊。
     *
     * @param seriesIds series 主鍵集合；空集合回傳空 Map
     * @return series 主鍵 → 基本資訊
     */
    Map<Long, SeriesBasicInfo> batchGetSeriesBasicInfo(Collection<Long> seriesIds);
}
