package dowob.xyz.blog.infrastructure.facade;

import dowob.xyz.blog.infrastructure.facade.dto.SeriesBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesNavigation;

import java.util.List;
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
     * 通知 series 模組：某 article 已被刪除，需要更新 series.article_count。
     *
     * <p>此方法為同步呼叫；對齊 ReadingFacade pattern。</p>
     *
     * @param seriesId article 原本所屬的 series id（必為非 null，由呼叫方檢查）
     */
    void notifyArticleDeletedFromSeries(Long seriesId);

    /**
     * 批次取得 articles 對應的 series 基本資訊（避免 N+1）。
     *
     * @param articleIds 候選 article id list
     * @return Map(articleId -> SeriesBasicInfo)；若 article 沒在 series 中則不在 Map 內
     */
    Map<Long, SeriesBasicInfo> batchGetSeriesBasicInfo(List<Long> articleIds);
}
