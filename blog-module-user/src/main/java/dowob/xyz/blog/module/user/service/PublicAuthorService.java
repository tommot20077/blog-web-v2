package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.exception.HttpStatusBusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.AuthorArticleStats;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.user.mapper.UserMapper;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.dto.response.AuthorSummaryResponse;
import dowob.xyz.blog.module.user.model.dto.response.PublicProfileResponse;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.validation.ProfileFieldLimits;
import dowob.xyz.blog.module.user.validation.PublicSocialLinks;
import dowob.xyz.blog.module.user.validation.SafeHttpUrlValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 公開作者服務：公開作者頁資料與作者下拉搜尋（匿名可讀）
 *
 * <p>資料歸屬：個人資料與 {@code social_links} 的解析屬 user 模組；「有已發布文章的人」與文章統計屬 article 模組，
 * 一律經 {@link ArticleFacade}（article 模組不 JOIN users，user 模組也不碰 articles 表）。</p>
 *
 * <p><b>界限</b>：搜尋結果固定最多 {@value #MAX_AUTHORS} 筆、keyword 最多 {@value #MAX_KEYWORD_LENGTH} 字，
 * 由本類執行期強制（judgment.md §9）。每次搜尋都向 article 模組取<b>全部</b>有已發布文章的作者，
 * 傳輸量與作者人數成正比，<b>作者超過約 1,000 人時重新評估</b>（改由 article 發布唯讀 view，或在 users 維護反正規化計數）；
 * 對 users 的查詢經 {@link BatchedQuery} 切批，不會因作者數而產生過大的 {@code IN} 清單。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class PublicAuthorService {

    /** 作者搜尋結果上限（與前端作者篩選 {@code authorUuids} 上限 20 對齊） */
    public static final int MAX_AUTHORS = 20;

    /** keyword 長度上限（trim 後，以 Unicode code point 計） */
    public static final int MAX_KEYWORD_LENGTH = 50;

    /** 排序：已發布篇數 DESC 之後的次要鍵——暱稱 ASC，再以 uuid ASC 打破平手，使結果確定 */
    private static final Comparator<User> NICKNAME_THEN_UUID =
            Comparator.comparing(User::getNickname).thenComparing(User::getUuid);

    /** 用戶資料存取 */
    private final UserRepository userRepository;

    /** 用戶 MyBatis Mapper（作者搜尋的 ACTIVE＋暱稱篩選） */
    private final UserMapper userMapper;

    /** 文章 Facade（作者統計與「有已發布文章的作者」） */
    private final ArticleFacade articleFacade;

    /**
     * 取得公開作者頁資料
     *
     * <p>只有 {@code ACTIVE} 使用者可見。使用者不存在、{@code DELETED}、{@code PENDING_VERIFICATION}（未驗證）、
     * {@code BANNED} 一律拋出<b>同一個</b> 404 {@code A0101}，錯誤碼、訊息與狀態碼完全相同，呼叫端無法區分。
     * 沒有已發布文章的 ACTIVE 使用者照常回傳，統計為 0。</p>
     *
     * <p>website 與 avatarUrl 以寫入端同一份規則再驗一次，不合格的舊資料回 null；socialLinks 經
     * {@link PublicSocialLinks} 寬鬆解析。</p>
     *
     * @param uuid 使用者公開 UUID
     * @return 公開作者頁資料（不含 email、username、role、通知偏好）
     * @throws HttpStatusBusinessException 404 {@link UserErrorCode#USER_NOT_FOUND}：不存在或非 ACTIVE
     */
    @Transactional(readOnly = true)
    public PublicProfileResponse getPublicProfile(UUID uuid) {
        User user = userRepository.findByUuid(uuid)
                .filter(found -> found.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new HttpStatusBusinessException(UserErrorCode.USER_NOT_FOUND, HttpStatus.NOT_FOUND));
        AuthorArticleStats stats = articleFacade.getPublishedStatsByAuthor(user.getUuid());
        return new PublicProfileResponse(
                user.getUuid(),
                user.getNickname(),
                safeUrlOrNull(user.getAvatarUrl(), ProfileFieldLimits.AVATAR_URL_MAX_LENGTH, true),
                user.getBio(),
                user.getLocation(),
                safeUrlOrNull(user.getWebsite(), ProfileFieldLimits.WEBSITE_MAX_LENGTH, false),
                PublicSocialLinks.from(user.getSocialLinks()),
                user.getCreatedAt(),
                new PublicProfileResponse.Stats(stats.count(), stats.views(), stats.likes()));
    }

    /**
     * 搜尋作者（作者下拉選單）
     *
     * <p>只列 ACTIVE 且至少 1 篇 PUBLISHED 文章的作者。{@code keyword} 選填：trim 後不分大小寫、任意位置比對暱稱，
     * {@code % _ \} 當字面字元；trim 後超過 {@value #MAX_KEYWORD_LENGTH} 字拋 400。
     * 排序：已發布篇數 DESC → 暱稱 ASC → uuid ASC，固定取前 {@value #MAX_AUTHORS} 筆。</p>
     *
     * @param keyword 暱稱關鍵字；null、空白視為不篩選
     * @return 作者清單，最多 {@value #MAX_AUTHORS} 筆
     * @throws BusinessException {@link CommonErrorCode#PARAM_VALID_ERROR}：keyword 超過 {@value #MAX_KEYWORD_LENGTH} 字
     */
    @Transactional(readOnly = true)
    public List<AuthorSummaryResponse> searchAuthors(String keyword) {
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_KEYWORD_LENGTH) {
            throw new BusinessException(CommonErrorCode.PARAM_VALID_ERROR);
        }
        Map<UUID, Long> publishedCounts = articleFacade.countPublishedByAuthor();
        if (publishedCounts.isEmpty()) {
            return List.of();
        }
        String pattern = trimmed.isEmpty() ? null : "%" + escapeLikePattern(trimmed) + "%";
        List<User> activeAuthors = BatchedQuery.queryInBatches(publishedCounts.keySet(),
                batch -> userMapper.findActiveAuthorsByUuids(batch, pattern));
        return activeAuthors.stream()
                .sorted(Comparator.<User>comparingLong(author -> publishedCounts.get(author.getUuid())).reversed()
                        .thenComparing(NICKNAME_THEN_UUID))
                .limit(MAX_AUTHORS)
                .map(author -> new AuthorSummaryResponse(author.getUuid(), author.getNickname(),
                        author.getAvatarUrl(), publishedCounts.get(author.getUuid())))
                .toList();
    }

    /**
     * 把使用者輸入跳脫成 {@code ILIKE} 的字面樣式（跳脫字元為反斜線）。
     *
     * @param raw 使用者輸入（已 trim）
     * @return 其中的 {@code \ % _} 皆以反斜線跳脫，不會被當成萬用字元
     */
    private static String escapeLikePattern(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 公開輸出時對舊資料的防禦：不合格的網址回 null。
     *
     * @param value                儲存值
     * @param maxLength            長度上限
     * @param allowFileContentPath 是否允許本站檔案內容路徑（頭貼）
     * @return 合格且非空的網址；否則 null
     */
    private static String safeUrlOrNull(String value, int maxLength, boolean allowFileContentPath) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return SafeHttpUrlValidator.isSafe(value, maxLength, allowFileContentPath) ? value : null;
    }
}
