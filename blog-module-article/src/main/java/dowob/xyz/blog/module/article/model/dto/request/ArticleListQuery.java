package dowob.xyz.blog.module.article.model.dto.request;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 公開文章列表的篩選與排序參數。
 *
 * <p><b>存在理由</b>：前端文章列表原本以 {@code size=1000} 取回全量後，在瀏覽器端完成
 * 篩選、排序與分頁；分頁上限若要從 1000 降到 100（SEC-04），篩選與排序就必須先搬到伺服器端，
 * 否則跨頁排序與跨頁篩選會靜默出錯。本型別即為該契約。</p>
 *
 * <p><b>與既有契約的關係（純新增、向下相容）</b>：不帶任何新參數時等同原本的
 * 「全部已發布文章」；{@code categorySlug} 沿用原參數名，由單值擴為多值（OR），
 * 原本的單值呼叫方式語意不變。與 {@code PageQuery} 並列綁定——兩者各自負責自己的不變式。</p>
 *
 * <p><b>正規化規則</b>（經 compact constructor 後保證成立，呼叫端無須再檢查）：</p>
 * <ul>
 *   <li>字串清單：去前後空白、轉小寫、去除空值與重複（保留首次出現順序）；回傳不可變清單，永不為 {@code null}。
 *       轉小寫不會錯過任何資料：標籤 slug 由 {@code SlugUtils} 產生、恆為小寫；分類 slug 自 V23 起
 *       由 DB CHECK 約束保證為小寫（見 {@code Category#SLUG_PATTERN}）。</li>
 *   <li>{@code authorUuids}：去除 {@code null} 與重複。
 *       格式錯誤的 UUID 在綁定階段即回 400——型別錯誤不屬於可正規化的範圍。</li>
 *   <li>三個多值清單<b>不在此截斷</b>：超過 {@link #MAX_VALUES_PER_FILTER} 時由
 *       {@code PublishedArticleCriteria#of} 拒絕（400）。截斷會讓結果不符合請求——
 *       OR 條件靜默少回文章、AND 條件靜默多回文章；本型別又無法在 constructor 內回 400
 *       （constructor 丟出的例外會被 Spring 包成 {@code BeanInstantiationException} 而成為 500）。</li>
 *   <li>{@code publishedWithinDays}：非正數視為不篩選（{@code null}），
 *       超過 {@link #MAX_PUBLISHED_WITHIN_DAYS} 夾為上限。</li>
 *   <li>{@code sort}：正規化為 {@link ArticleListSort#key()}，未知值退回 {@code latest}。</li>
 * </ul>
 *
 * @param tags                標籤 slug，AND 語意（文章須同時帶有全部標籤）
 * @param categorySlug        分類 slug，OR 語意（文章屬於其一即可）；沿用既有參數名以維持相容
 * @param authorUuids         作者公開 UUID，OR 語意
 * @param publishedWithinDays 只取最近 N 天內發布者；{@code null} 表示不篩選
 * @param sort                排序鍵，見 {@link ArticleListSort}
 * @author Yuan
 * @version 1.0
 */
public record ArticleListQuery(List<String> tags,
                               List<String> categorySlug,
                               List<UUID> authorUuids,
                               Integer publishedWithinDays,
                               String sort) {

    /**
     * 每個多值篩選參數的元素上限（由 {@code PublishedArticleCriteria#of} 強制，超過即回 400）。
     *
     * <p>必須有上限且不能交給 {@code BatchedQuery} 切批：這些條件位於帶
     * {@code ORDER BY ... LIMIT} 的分頁查詢內，標籤更是 AND 語意
     * （{@code GROUP BY ... HAVING COUNT(DISTINCT ...) = n}），屬跨批聚合，
     * 切批會靜默給出錯誤答案。取 20 遠高於任何 UI 的實際選取量，
     * 同時把單次查詢的 bind parameter 壓在數十以內。</p>
     */
    public static final int MAX_VALUES_PER_FILTER = 20;

    /**
     * {@code publishedWithinDays} 上限（約一百年）。
     *
     * <p>未夾界時 {@code Integer.MAX_VALUE} 天會算出西元前數百萬年的時間點，
     * 超出 PostgreSQL {@code timestamp} 可表示範圍而使查詢失敗；一百年在語意上已等同「不限」。</p>
     */
    public static final int MAX_PUBLISHED_WITHIN_DAYS = 36500;

    /**
     * 正規化篩選參數。
     *
     * <p>compact constructor 的參數為區域變數，對其賦值會被編譯器自動補上的
     * {@code this.x = x} 採用，因此此處的正規化即為最終存入欄位的值。</p>
     */
    public ArticleListQuery {
        tags = normalizeSlugs(tags);
        categorySlug = normalizeSlugs(categorySlug);
        authorUuids = authorUuids == null
                ? List.of()
                : authorUuids.stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        if (publishedWithinDays != null) {
            publishedWithinDays = publishedWithinDays <= 0
                    ? null
                    : Math.min(publishedWithinDays, MAX_PUBLISHED_WITHIN_DAYS);
        }
        sort = ArticleListSort.fromKey(sort).key();
    }

    /**
     * 不帶任何篩選的查詢：全部已發布文章，依最新發布排序。
     *
     * @return 不篩選的查詢
     */
    public static ArticleListQuery unfiltered() {
        return new ArticleListQuery(null, null, null, null, null);
    }

    /**
     * 取排序方式。
     *
     * @return 排序方式，永不為 {@code null}
     */
    public ArticleListSort sortOrder() {
        return ArticleListSort.fromKey(sort);
    }

    /**
     * 正規化 slug 清單：去空白、轉小寫、去空值與重複（去重在轉小寫之後進行）。
     *
     * @param values 原始清單，可為 {@code null} 或含 {@code null} 元素
     * @return 不可變的正規化清單，永不為 {@code null}
     */
    private static List<String> normalizeSlugs(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }
}
