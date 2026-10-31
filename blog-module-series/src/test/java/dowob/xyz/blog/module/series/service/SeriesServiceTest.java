package dowob.xyz.blog.module.series.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryResponse;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.module.series.exception.SeriesErrorCode;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.model.dto.request.CreateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.request.UpdateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.response.SeriesDetailResponse;
import dowob.xyz.blog.module.series.model.dto.response.SeriesSummaryResponse;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SeriesService} 單元測試。
 *
 * <p>M4-b 起整個 service 只以 UUID 識別使用者與文章：授權以 {@code AuthUser.uuid()} 對 {@code series.author_uuid}、
 * {@code ArticleData.authorUuid} 比對；作者資訊一頁只呼叫 {@code UserFacade.getDisplayInfoByUuids} 一次；
 * 文章端的 UUID 版 facade 取代 {@code ArticleQueryService}；是否為新成員由條件式寫入的結果決定（RACE-14）；
 * slug 唯一性由資料庫約束決定（RACE-15）；刪除於交易 commit 後才發事件。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SeriesService")
class SeriesServiceTest {

    @Mock private SeriesRepository repo;
    @Mock private SeriesMapper mapper;
    @Mock private ArticleFacade articleFacade;
    @Mock private ReadingFacade readingFacade;
    @Mock private UserFacade userFacade;
    @Mock private SeriesEventPublisher eventPublisher;

    /** 交易管理員（mock）：用來驗證 commit／rollback 與事件發送的先後 */
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    /** 真實的 TransactionTemplate（包裝 mock 的交易管理員），callback 會真的被執行 */
    @Spy private TransactionTemplate transactionTemplate = new TransactionTemplate(txManager);

    @InjectMocks private SeriesService service;

    private final UUID articleUuid = UUID.randomUUID();
    private final Long articleId = 200L;
    private final Long seriesId = 100L;
    private final UUID seriesUuid = UUID.randomUUID();

    /** 登入者（series 的擁有者）公開 UUID */
    private final UUID userUuid = UUID.randomUUID();

    /** 另一位使用者的公開 UUID */
    private final UUID otherUserUuid = UUID.randomUUID();

    // ── 測試資料 helper ─────────────────────────────────────────────────────

    private Series series(UUID authorUuid) {
        Series s = new Series();
        s.setId(seriesId);
        s.setUuid(seriesUuid);
        s.setTitle("Vue 101");
        s.setSlug("vue-101");
        s.setAuthorUuid(authorUuid);
        s.setArticleCount(0);
        return s;
    }

    private ArticleData article(UUID uuid, UUID authorUuid, ArticleStatus status, UUID inSeriesUuid, Integer position) {
        return new ArticleData(uuid, status.name(), position, authorUuid, inSeriesUuid);
    }

    /** 讓 series 自己的 mapper 回答「這個 series 仍存在」（供判斷文章的舊歸屬是不是殘留的 ghost）。 */
    private void otherSeriesExists(UUID other) {
        when(mapper.findExistingUuidsIn(List.of(other))).thenReturn(List.of(other.toString()));
    }

    private void stubAuthor(UUID uuid, String nickname) {
        lenient().when(userFacade.getDisplayInfoByUuids(anyCollection()))
                .thenReturn(Map.of(uuid, new UserDisplayInfo(uuid, nickname, "https://img/" + nickname)));
    }

    // ── createSeries（RACE-15：INSERT ... ON CONFLICT DO NOTHING）──────────────

    @Test
    @DisplayName("createSeries：以 INSERT ON CONFLICT 寫入，author_uuid 為登入者 uuid；不做 existsBySlug 先查")
    void createSeries_validRequest_insertsWithUuidAndAuthorUuid() {
        AtomicReference<Series> stored = new AtomicReference<>();
        when(mapper.insertIfSlugAbsent(any(Series.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            stored.get().setId(seriesId);
            return 1;
        });
        when(repo.findByUuid(any(UUID.class))).thenAnswer(inv -> Optional.of(stored.get()));
        stubAuthor(userUuid, "user");

        CreateSeriesRequest req = new CreateSeriesRequest();
        req.setTitle("Vue 101");
        req.setSlug("vue-101");
        req.setDescription("intro");
        LocalDateTime beforeCall = LocalDateTime.now();

        SeriesSummaryResponse resp = service.createSeries(userUuid, req);

        Series saved = stored.get();
        assertThat(saved.getUuid()).isNotNull();
        assertThat(saved.getAuthorUuid()).isEqualTo(userUuid);
        assertThat(saved.getTitle()).isEqualTo("Vue 101");
        assertThat(saved.getSlug()).isEqualTo("vue-101");
        assertThat(saved.getDescription()).isEqualTo("intro");
        assertThat(saved.getArticleCount()).isZero();
        /* 時間由 Java 傳入（JVM 時鐘），不依賴 DB 的 CURRENT_TIMESTAMP；建立時兩者相同 */
        assertThat(saved.getCreatedAt()).isNotNull().isBetween(beforeCall, LocalDateTime.now());
        assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
        verify(repo, never()).existsBySlug(any());
        verify(repo, never()).save(any());

        // 對外只出 UUID（architecture.md）；作者來自 UserFacade
        assertThat(resp.getUuid()).isEqualTo(saved.getUuid());
        assertThat(resp.getAuthor().getUuid()).isEqualTo(userUuid);
        assertThat(resp.getAuthor().getNickname()).isEqualTo("user");
    }

    @Test
    @DisplayName("createSeries：slug 已被使用（INSERT 影響 0 列）→ S0104，不再讀取也不查作者")
    void createSeries_duplicateSlug_throwsS0104() {
        when(mapper.insertIfSlugAbsent(any(Series.class))).thenReturn(0);

        CreateSeriesRequest req = new CreateSeriesRequest();
        req.setTitle("Vue 101");
        req.setSlug("vue-101");

        assertThatThrownBy(() -> service.createSeries(userUuid, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SLUG_ALREADY_USED.getMessage());

        verify(repo, never()).findByUuid(any());
        verifyNoInteractions(userFacade);
    }

    /** 寫入成功但 re-fetch 查不到（資料異常）時，應丟 BusinessException 而非 NPE。 */
    @Test
    @DisplayName("createSeries：寫入後 re-fetch 查無（資料異常）→ SERIES_NOT_FOUND 而非 NPE")
    void createSeries_refetchReturnsEmpty_throwsSeriesNotFound() {
        when(mapper.insertIfSlugAbsent(any(Series.class))).thenReturn(1);
        when(repo.findByUuid(any(UUID.class))).thenReturn(Optional.empty());

        CreateSeriesRequest req = new CreateSeriesRequest();
        req.setTitle("Vue 101");
        req.setSlug("vue-101");

        assertThatThrownBy(() -> service.createSeries(userUuid, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SERIES_NOT_FOUND.getMessage());
    }

    // ── updateSeries（RACE-15：單一 UPDATE ＋ 捕捉 DuplicateKeyException）──────────

    @Test
    @DisplayName("updateSeries：owner 以單一 UPDATE 寫入（不先 existsBySlug），再 re-fetch 回傳")
    void updateSeries_byOwner_singleUpdateThenRefetch() {
        Series existing = series(userUuid);
        Series updated = series(userUuid);
        updated.setTitle("new");
        updated.setSlug("new-slug");
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(existing), Optional.of(updated));
        when(mapper.updateBasicFields(eq(seriesId), eq("new"), eq("new-slug"), isNull(), isNull(), any(LocalDateTime.class)))
                .thenReturn(1);
        stubAuthor(userUuid, "user");

        UpdateSeriesRequest req = new UpdateSeriesRequest();
        req.setTitle("new");
        req.setSlug("new-slug");
        LocalDateTime beforeCall = LocalDateTime.now();

        SeriesSummaryResponse resp = service.updateSeries(seriesUuid, userUuid, false, req);

        ArgumentCaptor<LocalDateTime> updatedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).updateBasicFields(eq(seriesId), eq("new"), eq("new-slug"), isNull(), isNull(), updatedAt.capture());
        assertThat(updatedAt.getValue()).as("updated_at 由 Java 傳入（JVM 時鐘）")
                .isBetween(beforeCall, LocalDateTime.now());
        verify(repo, never()).existsBySlug(any());
        verify(repo, never()).save(any());
        assertThat(resp.getUuid()).isEqualTo(seriesUuid);
        assertThat(resp.getTitle()).isEqualTo("new");
        assertThat(resp.getSlug()).isEqualTo("new-slug");
        assertThat(resp.getAuthor().getNickname()).isEqualTo("user");
    }

    @Test
    @DisplayName("updateSeries：slug 撞唯一約束（DuplicateKeyException）→ S0104，且 UPDATE 之後不再有任何 DB 操作")
    void updateSeries_duplicateKey_throwsS0104AndNoMoreDbOperations() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(mapper.updateBasicFields(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("series_slug_key"));

        UpdateSeriesRequest req = new UpdateSeriesRequest();
        req.setSlug("taken");

        assertThatThrownBy(() -> service.updateSeries(seriesUuid, userUuid, false, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SLUG_ALREADY_USED.getMessage());

        // PostgreSQL 的交易在錯誤後即中止：捕捉後不得再執行任何 SQL（含 re-fetch 與查作者）
        verify(repo, times(1)).findByUuid(seriesUuid);
        verify(mapper, times(1)).updateBasicFields(any(), any(), any(), any(), any(), any());
        verifyNoMoreInteractions(repo, mapper);
        verifyNoInteractions(userFacade);
    }

    @Test
    @DisplayName("updateSeries：更新後 re-fetch 查無（資料異常）→ SERIES_NOT_FOUND 而非 NPE")
    void updateSeries_refetchReturnsEmpty_throwsSeriesNotFound() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)), Optional.empty());
        when(mapper.updateBasicFields(any(), any(), any(), any(), any(), any())).thenReturn(1);

        UpdateSeriesRequest req = new UpdateSeriesRequest();
        req.setTitle("new");

        assertThatThrownBy(() -> service.updateSeries(seriesUuid, userUuid, false, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SERIES_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("updateSeries：非 owner 也非 admin → S0102，不寫入（owner 以 uuid 比對）")
    void updateSeries_byNonOwnerNonAdmin_throwsS0102() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(otherUserUuid)));

        UpdateSeriesRequest req = new UpdateSeriesRequest();
        req.setTitle("hijack");

        assertThatThrownBy(() -> service.updateSeries(seriesUuid, userUuid, false, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SERIES_ACCESS_DENIED.getMessage());

        verify(mapper, never()).updateBasicFields(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("updateSeries：admin 可更新他人的 series")
    void updateSeries_byAdmin_succeeds() {
        Series existing = series(otherUserUuid);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(existing), Optional.of(existing));
        when(mapper.updateBasicFields(any(), any(), any(), any(), any(), any())).thenReturn(1);
        stubAuthor(otherUserUuid, "owner");

        UpdateSeriesRequest req = new UpdateSeriesRequest();
        req.setTitle("admin override");

        service.updateSeries(seriesUuid, userUuid, /* isAdmin */ true, req);

        verify(mapper).updateBasicFields(eq(seriesId), eq("admin override"), isNull(), isNull(), isNull(),
                any(LocalDateTime.class));
    }

    // ── deleteSeries（commit 後才發事件）─────────────────────────────────────

    @Test
    @DisplayName("deleteSeries：owner 刪除 → 先刪除、交易 commit，之後才發 SeriesDeletedEvent")
    void deleteSeries_byOwner_deletesThenCommitsThenPublishesEvent() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));

        service.deleteSeries(seriesUuid, userUuid, false);

        InOrder order = inOrder(repo, txManager, eventPublisher);
        order.verify(repo).deleteById(seriesId);
        order.verify(txManager).commit(any());
        order.verify(eventPublisher).publishDeleted(seriesUuid);
        // ON DELETE SET NULL 由 DB 處理；service 不需顯式 update articles
    }

    @Test
    @DisplayName("deleteSeries：series 不存在 → S0101，交易 rollback，不發事件")
    void deleteSeries_notFound_throwsAndPublishesNothing() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteSeries(seriesUuid, userUuid, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SERIES_NOT_FOUND.getMessage());

        verify(txManager, never()).commit(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("deleteSeries：非 owner 也非 admin → S0102，不刪除、不發事件")
    void deleteSeries_byNonOwner_throwsS0102AndPublishesNothing() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(otherUserUuid)));

        assertThatThrownBy(() -> service.deleteSeries(seriesUuid, userUuid, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SERIES_ACCESS_DENIED.getMessage());

        verify(repo, never()).deleteById(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("deleteSeries：刪除時 DB 失敗（交易 rollback）→ 例外往外拋，不發事件")
    void deleteSeries_dbFailure_doesNotPublishEvent() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        doThrow(new IllegalStateException("DB down")).when(repo).deleteById(seriesId);

        assertThatThrownBy(() -> service.deleteSeries(seriesUuid, userUuid, false))
                .isInstanceOf(IllegalStateException.class);

        verify(txManager, never()).commit(any());
        verifyNoInteractions(eventPublisher);
    }

    // ── addArticleToSeries（RACE-14：由寫入本身決定是否為新成員）─────────────────

    @Test
    @DisplayName("addArticleToSeries：條件式指派成功（新成員）→ article_count +1，不另外更新位置")
    void addArticleToSeries_newMember_incrementsCount() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid))
                .thenReturn(Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, null, null)));
        when(articleFacade.assignToSeriesIfUnassigned(articleUuid, seriesUuid, 3)).thenReturn(true);
        when(mapper.incrementArticleCount(seriesId)).thenReturn(1);

        service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 3);

        verify(mapper).incrementArticleCount(seriesId);
        verify(articleFacade, times(1)).findByUuid(articleUuid);
    }

    @Test
    @DisplayName("addArticleToSeries：已在同一 series（指派回 false、重讀仍在此 series）→ 以條件式 UPDATE 只更新位置，不 +1，也不走讀改寫")
    void addArticleToSeries_sameSeries_updatesPositionOnly() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid))
                .thenReturn(Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, seriesUuid, 2)));
        when(articleFacade.assignToSeriesIfUnassigned(articleUuid, seriesUuid, 5)).thenReturn(false);
        when(articleFacade.updateSeriesPositionIfAssigned(articleUuid, seriesUuid, 5)).thenReturn(true);

        service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 5);

        verify(articleFacade).updateSeriesPositionIfAssigned(articleUuid, seriesUuid, 5);
        verify(mapper, never()).incrementArticleCount(any());
    }

    @Test
    @DisplayName("addArticleToSeries：條件式更新位置回 false（文章剛被移出）→ 回到迴圈重新指派，成功才 +1")
    void addArticleToSeries_positionUpdateLostRace_loopsBackAndAssigns() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, seriesUuid, 2)),
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, seriesUuid, 2)));
        when(articleFacade.assignToSeriesIfUnassigned(articleUuid, seriesUuid, 5)).thenReturn(false, true);
        when(articleFacade.updateSeriesPositionIfAssigned(articleUuid, seriesUuid, 5)).thenReturn(false);
        when(mapper.incrementArticleCount(seriesId)).thenReturn(1);

        service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 5);

        verify(articleFacade, times(2)).assignToSeriesIfUnassigned(articleUuid, seriesUuid, 5);
        verify(mapper, times(1)).incrementArticleCount(seriesId);
    }

    @Test
    @DisplayName("addArticleToSeries：兩次嘗試歸屬都被併發改變 → 回報併發衝突 A0208（不是 S0106），不 +1")
    void addArticleToSeries_ownershipKeepsChanging_throwsConcurrentUpdate() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid))
                .thenReturn(Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, seriesUuid, 2)));
        when(articleFacade.assignToSeriesIfUnassigned(articleUuid, seriesUuid, 5)).thenReturn(false);
        when(articleFacade.updateSeriesPositionIfAssigned(articleUuid, seriesUuid, 5)).thenReturn(false);

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ArticleErrorCode.ARTICLE_CONCURRENT_UPDATE.getMessage());

        verify(mapper, never()).incrementArticleCount(any());
    }

    @Test
    @DisplayName("addArticleToSeries：預讀時文章在另一個 series → S0106，不嘗試指派")
    void addArticleToSeries_articleAlreadyInOtherSeries_throwsS0106() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        UUID otherSeries = UUID.randomUUID();
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                article(articleUuid, userUuid, ArticleStatus.PUBLISHED, otherSeries, 1)));
        otherSeriesExists(otherSeries);

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_IN_OTHER_SERIES.getMessage());

        verify(articleFacade, never()).assignToSeriesIfUnassigned(any(), any(), any());
        verify(mapper, never()).incrementArticleCount(any());
    }

    @Test
    @DisplayName("addArticleToSeries：指派回 false 且重讀發現被併發加入了另一個 series → S0106，不 +1")
    void addArticleToSeries_lostRaceToOtherSeries_throwsS0106() {
        UUID otherSeries = UUID.randomUUID();
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, null, null)),
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, otherSeries, 1)));
        when(articleFacade.assignToSeriesIfUnassigned(articleUuid, seriesUuid, 1)).thenReturn(false);
        otherSeriesExists(otherSeries);

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_IN_OTHER_SERIES.getMessage());

        verify(mapper, never()).incrementArticleCount(any());
    }

    @Test
    @DisplayName("addArticleToSeries：指派回 false 後重讀發現已被併發移出（無 series）→ 重試一次，成功才 +1")
    void addArticleToSeries_releasedBetweenAttempts_retriesOnceAndIncrements() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, null, null)),
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, null, null)));
        when(articleFacade.assignToSeriesIfUnassigned(articleUuid, seriesUuid, 1)).thenReturn(false, true);
        when(mapper.incrementArticleCount(seriesId)).thenReturn(1);

        service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 1);

        verify(articleFacade, times(2)).assignToSeriesIfUnassigned(articleUuid, seriesUuid, 1);
        verify(mapper, times(1)).incrementArticleCount(seriesId);
    }

    @Test
    @DisplayName("addArticleToSeries：文章殘留的舊歸屬指向已被刪除的 series（事件尚未處理）→ 條件式清掉殘留、不動 article_count，重讀後正常加入")
    void addArticleToSeries_staleMembershipOfDeletedSeries_isReleasedThenAssigned() {
        UUID ghostSeries = UUID.randomUUID();
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, ghostSeries, 4)),
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, null, null)));
        when(mapper.findExistingUuidsIn(List.of(ghostSeries))).thenReturn(List.of());
        when(articleFacade.removeFromSeriesIfAssigned(articleUuid, ghostSeries)).thenReturn(true);
        when(articleFacade.assignToSeriesIfUnassigned(articleUuid, seriesUuid, 1)).thenReturn(true);
        when(mapper.incrementArticleCount(seriesId)).thenReturn(1);

        service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 1);

        verify(articleFacade).removeFromSeriesIfAssigned(articleUuid, ghostSeries);
        verify(mapper, never()).decrementArticleCount(any());
        verify(mapper, times(1)).incrementArticleCount(seriesId);
    }

    @Test
    @DisplayName("addArticleToSeries：舊歸屬的 series 仍存在 → 維持 S0106，不清除、不指派")
    void addArticleToSeries_membershipOfExistingOtherSeries_staysS0106() {
        UUID realOther = UUID.randomUUID();
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                article(articleUuid, userUuid, ArticleStatus.PUBLISHED, realOther, 1)));
        otherSeriesExists(realOther);

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_IN_OTHER_SERIES.getMessage());

        verify(articleFacade, never()).removeFromSeriesIfAssigned(any(), any());
        verify(articleFacade, never()).assignToSeriesIfUnassigned(any(), any(), any());
    }

    @Test
    @DisplayName("addArticleToSeries：重試迴圈重讀後才發現殘留的 ghost 歸屬 → 套用同一個判斷，清掉後重試成功")
    void addArticleToSeries_ghostFoundOnReread_isReleasedAndRetried() {
        UUID ghostSeries = UUID.randomUUID();
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, null, null)),
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, ghostSeries, 2)),
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, null, null)));
        when(mapper.findExistingUuidsIn(List.of(ghostSeries))).thenReturn(List.of());
        when(articleFacade.removeFromSeriesIfAssigned(articleUuid, ghostSeries)).thenReturn(true);
        when(articleFacade.assignToSeriesIfUnassigned(articleUuid, seriesUuid, 1)).thenReturn(false, true);
        when(mapper.incrementArticleCount(seriesId)).thenReturn(1);

        service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 1);

        verify(articleFacade).removeFromSeriesIfAssigned(articleUuid, ghostSeries);
        verify(articleFacade, times(2)).assignToSeriesIfUnassigned(articleUuid, seriesUuid, 1);
        verify(mapper, never()).decrementArticleCount(any());
    }

    @Test
    @DisplayName("addArticleToSeries：指派成功但 article_count +1 影響 0 列（series 在讀取後被刪除）→ 拋 S0101 讓整個交易回滾，ghost 歸屬不落地")
    void addArticleToSeries_incrementAffectsNothing_throwsSeriesNotFound() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid))
                .thenReturn(Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, null, null)));
        when(articleFacade.assignToSeriesIfUnassigned(articleUuid, seriesUuid, 3)).thenReturn(true);
        when(mapper.incrementArticleCount(seriesId)).thenReturn(0);

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 3))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SERIES_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("addArticleToSeries：重試仍失敗（文章一直被併發移出）→ 併發衝突 A0208，不 +1")
    void addArticleToSeries_retryAlsoFails_throwsConcurrentUpdate() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, null, null)),
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, null, null)));
        when(articleFacade.assignToSeriesIfUnassigned(articleUuid, seriesUuid, 1)).thenReturn(false, false);

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ArticleErrorCode.ARTICLE_CONCURRENT_UPDATE.getMessage());

        verify(mapper, never()).incrementArticleCount(any());
    }

    @Test
    @DisplayName("addArticleToSeries：指派回 false 後重讀發現文章已被刪除 → S0107")
    void addArticleToSeries_articleDeletedBetweenAttempts_throwsS0107() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, null, null)),
                Optional.empty());
        when(articleFacade.assignToSeriesIfUnassigned(articleUuid, seriesUuid, 1)).thenReturn(false);

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("addArticleToSeries：草稿文章 → S0103，不指派")
    void addArticleToSeries_draftArticle_throwsS0103() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid))
                .thenReturn(Optional.of(article(articleUuid, userUuid, ArticleStatus.DRAFT, null, null)));

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_NOT_PUBLISHED.getMessage());

        verify(articleFacade, never()).assignToSeriesIfUnassigned(any(), any(), any());
    }

    @Test
    @DisplayName("addArticleToSeries：非 admin 加入他人的文章（authorUuid 不同）→ S0102，不指派")
    void addArticleToSeries_otherAuthorsArticle_throwsS0102() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(
                Optional.of(article(articleUuid, otherUserUuid, ArticleStatus.PUBLISHED, null, null)));

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SERIES_ACCESS_DENIED.getMessage());

        verify(articleFacade, never()).assignToSeriesIfUnassigned(any(), any(), any());
    }

    @Test
    @DisplayName("addArticleToSeries：文章 authorUuid 為 null（資料異常）→ 非 admin 一律拒絕（S0102），不 NPE")
    void addArticleToSeries_articleWithoutAuthorUuid_isDeniedForNonAdmin() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(
                Optional.of(article(articleUuid, null, ArticleStatus.PUBLISHED, null, null)));

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SERIES_ACCESS_DENIED.getMessage());
    }

    @Test
    @DisplayName("addArticleToSeries：admin 可加入他人的文章到他人的 series")
    void addArticleToSeries_admin_canAddOthersArticleToOthersSeries() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(otherUserUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(
                Optional.of(article(articleUuid, UUID.randomUUID(), ArticleStatus.PUBLISHED, null, null)));
        when(articleFacade.assignToSeriesIfUnassigned(articleUuid, seriesUuid, 1)).thenReturn(true);
        when(mapper.incrementArticleCount(seriesId)).thenReturn(1);

        service.addArticleToSeries(seriesUuid, articleUuid, userUuid, /* isAdmin */ true, 1);

        verify(mapper).incrementArticleCount(seriesId);
    }

    @Test
    @DisplayName("addArticleToSeries：series 的 owner 不是登入者 → S0102（以 uuid 比對），不讀文章")
    void addArticleToSeries_notSeriesOwner_throwsS0102() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(otherUserUuid)));

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SERIES_ACCESS_DENIED.getMessage());

        verifyNoInteractions(articleFacade);
    }

    @Test
    @DisplayName("addArticleToSeries：文章不存在 → S0107")
    void addArticleToSeries_articleNotFound_throwsS0107() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userUuid, false, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_NOT_FOUND.getMessage());
    }

    // ── removeArticleFromSeries（RACE-14：條件式移出）────────────────────────────

    @Test
    @DisplayName("removeArticleFromSeries：條件式移出成功 → article_count -1")
    void removeArticleFromSeries_decrementsCount() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, seriesUuid, 3)));
        when(articleFacade.removeFromSeriesIfAssigned(articleUuid, seriesUuid)).thenReturn(true);

        service.removeArticleFromSeries(seriesUuid, articleUuid, userUuid, false);

        verify(mapper).decrementArticleCount(seriesId);
    }

    @Test
    @DisplayName("removeArticleFromSeries：條件式移出回 false（已被併發移出）→ S0105，不 -1")
    void removeArticleFromSeries_lostRace_throwsS0105AndDoesNotDecrement() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, seriesUuid, 3)));
        when(articleFacade.removeFromSeriesIfAssigned(articleUuid, seriesUuid)).thenReturn(false);

        assertThatThrownBy(() -> service.removeArticleFromSeries(seriesUuid, articleUuid, userUuid, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_NOT_IN_SERIES.getMessage());

        verify(mapper, never()).decrementArticleCount(any());
    }

    @Test
    @DisplayName("removeArticleFromSeries：文章不在這個 series → S0105，不嘗試移出")
    void removeArticleFromSeries_articleNotInThisSeries_throwsS0105() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(
                Optional.of(article(articleUuid, userUuid, ArticleStatus.PUBLISHED, null, null)));

        assertThatThrownBy(() -> service.removeArticleFromSeries(seriesUuid, articleUuid, userUuid, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_NOT_IN_SERIES.getMessage());

        verify(articleFacade, never()).removeFromSeriesIfAssigned(any(), any());
    }

    @Test
    @DisplayName("removeArticleFromSeries：文章在另一個 series → S0105")
    void removeArticleFromSeries_articleInOtherSeries_throwsS0105() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                article(articleUuid, userUuid, ArticleStatus.PUBLISHED, UUID.randomUUID(), 1)));

        assertThatThrownBy(() -> service.removeArticleFromSeries(seriesUuid, articleUuid, userUuid, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_NOT_IN_SERIES.getMessage());
    }

    @Test
    @DisplayName("removeArticleFromSeries：文章不存在 → S0107")
    void removeArticleFromSeries_articleNotFound_throwsS0107() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(userUuid)));
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeArticleFromSeries(seriesUuid, articleUuid, userUuid, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.ARTICLE_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("removeArticleFromSeries：series 的 owner 不是登入者 → S0102（以 uuid 比對）")
    void removeArticleFromSeries_notSeriesOwner_throwsS0102() {
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series(otherUserUuid)));

        assertThatThrownBy(() -> service.removeArticleFromSeries(seriesUuid, articleUuid, userUuid, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SERIES_ACCESS_DENIED.getMessage());

        verifyNoInteractions(articleFacade);
    }

    // ── getSeriesDetail ───────────────────────────────────────────────────

    @Test
    @DisplayName("getSeriesDetail：已登入 → 含 myProgress（readCount、totalCount、nextUnread）")
    void getSeriesDetail_authenticated_includesMyProgress() {
        Series row = series(userUuid);
        row.setArticleCount(3);
        when(repo.findBySlug("vue-101")).thenReturn(Optional.of(row));
        stubAuthor(userUuid, "user");

        UUID uuidA = UUID.randomUUID();
        UUID uuidB = UUID.randomUUID();
        UUID uuidC = UUID.randomUUID();
        when(articleFacade.findBySeriesUuidOrderByPosition(seriesUuid)).thenReturn(List.of(
                article(uuidA, userUuid, ArticleStatus.PUBLISHED, seriesUuid, 1),
                article(uuidB, userUuid, ArticleStatus.PUBLISHED, seriesUuid, 2),
                article(uuidC, userUuid, ArticleStatus.PUBLISHED, seriesUuid, 3)));
        lenient().when(articleFacade.getSummariesByUuids(anyList())).thenReturn(List.of());
        lenient().when(readingFacade.batchGetProgress(eq(userUuid), eq(List.of(uuidA, uuidB, uuidC))))
                .thenReturn(Map.of(uuidA, new BigDecimal("0.98"), uuidB, new BigDecimal("0.50")));

        SeriesDetailResponse resp = service.getSeriesDetail("vue-101", userUuid);

        assertThat(resp.getMyProgress()).isNotNull();
        // A 進度 0.98 >= 0.95 → 已讀；B = 0.50 / C 無紀錄 → 未讀
        assertThat(resp.getMyProgress().getReadCount()).isEqualTo(1);
        assertThat(resp.getMyProgress().getTotalCount()).isEqualTo(3);
        assertThat(resp.getMyProgress().getNextUnreadArticleUuid()).isEqualTo(uuidB);
    }

    @Test
    @DisplayName("getSeriesDetail：文章摘要走 ArticleFacade.getSummariesByUuids，並以本 series 覆寫 seriesUuid／seriesTitle")
    void getSeriesDetail_withArticles_usesSummariesByUuidsAndOverwritesSeriesFields() {
        when(repo.findBySlug("vue-101")).thenReturn(Optional.of(series(userUuid)));
        stubAuthor(userUuid, "user");
        UUID uuidA = UUID.randomUUID();
        when(articleFacade.findBySeriesUuidOrderByPosition(seriesUuid)).thenReturn(List.of(
                article(uuidA, userUuid, ArticleStatus.PUBLISHED, seriesUuid, 1)));
        ArticleSummaryResponse summary = ArticleSummaryResponse.builder()
                .uuid(uuidA).title("A").seriesPosition(1).build();
        when(articleFacade.getSummariesByUuids(List.of(uuidA))).thenReturn(List.of(summary));

        SeriesDetailResponse resp = service.getSeriesDetail("vue-101", null);

        assertThat(resp.getArticles()).hasSize(1);
        assertThat(resp.getArticles().get(0).getUuid()).isEqualTo(uuidA);
        assertThat(resp.getArticles().get(0).getSeriesUuid()).isEqualTo(seriesUuid);
        assertThat(resp.getArticles().get(0).getSeriesTitle()).isEqualTo("Vue 101");
    }

    @Test
    @DisplayName("getSeriesDetail：作者只呼叫 UserFacade.getDisplayInfoByUuids 一次，欄位對得上")
    void getSeriesDetail_resolvesAuthorWithSingleUserFacadeCall() {
        when(repo.findBySlug("vue-101")).thenReturn(Optional.of(series(userUuid)));
        stubAuthor(userUuid, "Alice");
        when(articleFacade.findBySeriesUuidOrderByPosition(seriesUuid)).thenReturn(List.of());

        SeriesDetailResponse resp = service.getSeriesDetail("vue-101", null);

        verify(userFacade, times(1)).getDisplayInfoByUuids(anyCollection());
        assertThat(resp.getAuthor().getUuid()).isEqualTo(userUuid);
        assertThat(resp.getAuthor().getNickname()).isEqualTo("Alice");
        assertThat(resp.getAuthor().getAvatarUrl()).isEqualTo("https://img/Alice");
    }

    @Test
    @DisplayName("getSeriesDetail：查無作者（與舊 LEFT JOIN 無匹配相同）→ 作者欄位皆為 null")
    void getSeriesDetail_authorNotFound_authorFieldsAreNull() {
        when(repo.findBySlug("vue-101")).thenReturn(Optional.of(series(userUuid)));
        when(userFacade.getDisplayInfoByUuids(anyCollection())).thenReturn(Map.of());
        when(articleFacade.findBySeriesUuidOrderByPosition(seriesUuid)).thenReturn(List.of());

        SeriesDetailResponse resp = service.getSeriesDetail("vue-101", null);

        assertThat(resp.getAuthor()).isNotNull();
        assertThat(resp.getAuthor().getUuid()).isNull();
        assertThat(resp.getAuthor().getNickname()).isNull();
        assertThat(resp.getAuthor().getAvatarUrl()).isNull();
    }

    @Test
    @DisplayName("getSeriesDetail：slug 不存在 → S0101")
    void getSeriesDetail_unknownSlug_throwsS0101() {
        when(repo.findBySlug("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSeriesDetail("nope", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SERIES_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("getSeriesDetail：只對外公開 PUBLISHED；非公開文章不進 enrich 查詢，進度分母與 nextUnread 只算公開文章")
    void getSeriesDetail_mixedStatuses_exposesPublishedOnly() {
        Series row = series(userUuid);
        row.setArticleCount(5);
        when(repo.findBySlug("vue-101")).thenReturn(Optional.of(row));
        stubAuthor(userUuid, "user");

        UUID publishedUuid = UUID.randomUUID();
        when(articleFacade.findBySeriesUuidOrderByPosition(seriesUuid)).thenReturn(List.of(
                article(publishedUuid, userUuid, ArticleStatus.PUBLISHED, seriesUuid, 1),
                article(UUID.randomUUID(), userUuid, ArticleStatus.DRAFT, seriesUuid, 2),
                article(UUID.randomUUID(), userUuid, ArticleStatus.REJECTED, seriesUuid, 3),
                article(UUID.randomUUID(), userUuid, ArticleStatus.ARCHIVED, seriesUuid, 4),
                article(UUID.randomUUID(), userUuid, ArticleStatus.PENDING_REVIEW, seriesUuid, 5)));
        ArticleSummaryResponse publishedSummary = ArticleSummaryResponse.builder()
                .uuid(publishedUuid).title("A").seriesPosition(1).build();
        when(articleFacade.getSummariesByUuids(List.of(publishedUuid))).thenReturn(List.of(publishedSummary));
        // 唯一的 PUBLISHED 文章已讀完，故公開視角下沒有下一篇未讀
        lenient().when(readingFacade.batchGetProgress(eq(userUuid), any()))
                .thenReturn(Map.of(publishedUuid, new BigDecimal("0.98")));

        SeriesDetailResponse resp = service.getSeriesDetail("vue-101", userUuid);

        // 非 PUBLISHED 不得進入 enrich 查詢，否則 title/slug/summary/content 會流到匿名訪客
        verify(articleFacade).getSummariesByUuids(List.of(publishedUuid));
        assertThat(resp.getArticles()).hasSize(1);
        assertThat(resp.getArticles().get(0).getUuid()).isEqualTo(publishedUuid);
        assertThat(resp.getMyProgress().getReadCount()).isEqualTo(1);
        assertThat(resp.getMyProgress().getTotalCount()).isEqualTo(1);
        assertThat(resp.getMyProgress().getNextUnreadArticleUuid()).isNull();
    }

    @Test
    @DisplayName("getSeriesDetail：articleCount 反映 PUBLISHED 數，不沿用含非公開文章的 series.article_count（finding #1）")
    void getSeriesDetail_mixedStatuses_articleCountReflectsPublishedOnly() {
        Series row = series(userUuid);
        row.setArticleCount(3);
        when(repo.findBySlug("vue-101")).thenReturn(Optional.of(row));
        stubAuthor(userUuid, "user");

        UUID publishedUuid = UUID.randomUUID();
        when(articleFacade.findBySeriesUuidOrderByPosition(seriesUuid)).thenReturn(List.of(
                article(publishedUuid, userUuid, ArticleStatus.PUBLISHED, seriesUuid, 1),
                article(UUID.randomUUID(), userUuid, ArticleStatus.DRAFT, seriesUuid, 2),
                article(UUID.randomUUID(), userUuid, ArticleStatus.ARCHIVED, seriesUuid, 3)));
        when(articleFacade.getSummariesByUuids(List.of(publishedUuid)))
                .thenReturn(List.of(ArticleSummaryResponse.builder().uuid(publishedUuid).title("A").build()));

        SeriesDetailResponse resp = service.getSeriesDetail("vue-101", null);

        assertThat(resp.getArticleCount()).isEqualTo(1);
        assertThat(resp.getArticles()).hasSize(1);
    }

    @Test
    @DisplayName("getSeriesDetail：無任何 PUBLISHED 文章 → 200 空清單、articleCount 0，且不對未公開內容做任何 enrich 查詢（finding #3）")
    void getSeriesDetail_noPublishedArticles_returnsEmptyArticles() {
        Series row = series(userUuid);
        row.setSlug("draft-only");
        row.setArticleCount(2);
        when(repo.findBySlug("draft-only")).thenReturn(Optional.of(row));
        stubAuthor(userUuid, "user");
        when(articleFacade.findBySeriesUuidOrderByPosition(seriesUuid)).thenReturn(List.of(
                article(UUID.randomUUID(), userUuid, ArticleStatus.DRAFT, seriesUuid, 1),
                article(UUID.randomUUID(), userUuid, ArticleStatus.PENDING_REVIEW, seriesUuid, 2)));

        SeriesDetailResponse resp = service.getSeriesDetail("draft-only", null);

        assertThat(resp.getArticleCount()).isZero();
        assertThat(resp.getArticles()).isEmpty();
        verify(articleFacade, never()).getSummariesByUuids(any());
    }

    // ── listPublic（三段式）──────────────────────────────────────────────

    private Series listRow(UUID uuid, String slug, UUID authorUuid) {
        Series s = new Series();
        s.setId((long) slug.hashCode());
        s.setUuid(uuid);
        s.setSlug(slug);
        s.setAuthorUuid(authorUuid);
        return s;
    }

    @Test
    @DisplayName("listPublic：只列出有公開文章的 series，articleCount 為即時計數；作者整頁只查一次")
    void listPublicOnlyListsSeriesWithPublishedArticlesAndArticleCountIsLive() {
        UUID s10 = UUID.randomUUID();
        UUID s20 = UUID.randomUUID();
        UUID s30 = UUID.randomUUID();
        UUID authorA = UUID.randomUUID();
        UUID authorB = UUID.randomUUID();
        when(mapper.findAllUuidsOrderByCreatedAtDesc()).thenReturn(List.of(s10, s20, s30));
        when(articleFacade.countPublishedBySeriesUuids(List.of(s10, s20, s30)))
                .thenReturn(Map.of(s10, 3, s30, 1));
        when(mapper.findByUuids(List.of(s10, s30)))
                .thenReturn(List.of(listRow(s10, "s10", authorA), listRow(s30, "s30", authorB)));
        when(userFacade.getDisplayInfoByUuids(anyCollection())).thenReturn(Map.of(
                authorA, new UserDisplayInfo(authorA, "A", null),
                authorB, new UserDisplayInfo(authorB, "B", null)));

        PageResult<SeriesSummaryResponse> result = service.listPublic(1, 20);

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getRecords().get(0).getArticleCount()).isEqualTo(3);
        assertThat(result.getRecords().get(1).getArticleCount()).isEqualTo(1);
        assertThat(result.getRecords().get(0).getAuthor().getNickname()).isEqualTo("A");
        assertThat(result.getRecords().get(1).getAuthor().getNickname()).isEqualTo("B");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(userFacade, times(1)).getDisplayInfoByUuids(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(authorA, authorB);
    }

    @Test
    @DisplayName("listPublic：同一作者的多個 series → 作者去重後只送出一個 uuid")
    void listPublicDeduplicatesAuthorsBeforeSingleUserFacadeCall() {
        UUID s10 = UUID.randomUUID();
        UUID s20 = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        when(mapper.findAllUuidsOrderByCreatedAtDesc()).thenReturn(List.of(s10, s20));
        when(articleFacade.countPublishedBySeriesUuids(List.of(s10, s20))).thenReturn(Map.of(s10, 1, s20, 1));
        when(mapper.findByUuids(List.of(s10, s20)))
                .thenReturn(List.of(listRow(s10, "s10", author), listRow(s20, "s20", author)));
        when(userFacade.getDisplayInfoByUuids(anyCollection())).thenReturn(Map.of());

        service.listPublic(1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(userFacade, times(1)).getDisplayInfoByUuids(captor.capture());
        assertThat(captor.getValue()).containsExactly(author);
    }

    @Test
    @DisplayName("listPublic：第二頁只取該頁的 uuid")
    void listPublicSecondPageOnlyTakesUuidsOfThatPage() {
        UUID s10 = UUID.randomUUID();
        UUID s20 = UUID.randomUUID();
        UUID s30 = UUID.randomUUID();
        when(mapper.findAllUuidsOrderByCreatedAtDesc()).thenReturn(List.of(s10, s20, s30));
        when(articleFacade.countPublishedBySeriesUuids(List.of(s10, s20, s30)))
                .thenReturn(Map.of(s10, 1, s20, 1, s30, 1));
        when(mapper.findByUuids(List.of(s30))).thenReturn(List.of(listRow(s30, "s30", UUID.randomUUID())));
        when(userFacade.getDisplayInfoByUuids(anyCollection())).thenReturn(Map.of());

        PageResult<SeriesSummaryResponse> result = service.listPublic(2, 2);

        assertThat(result.getTotal()).isEqualTo(3L);
        assertThat(result.getRecords()).hasSize(1);
    }

    @Test
    @DisplayName("listPublic：全無公開文章時回空頁，且不查明細也不查作者")
    void listPublicWhenNoPublishedArticles_returnsEmptyPageWithoutDetailOrAuthorQuery() {
        UUID s10 = UUID.randomUUID();
        UUID s20 = UUID.randomUUID();
        when(mapper.findAllUuidsOrderByCreatedAtDesc()).thenReturn(List.of(s10, s20));
        when(articleFacade.countPublishedBySeriesUuids(List.of(s10, s20))).thenReturn(Map.of());

        PageResult<SeriesSummaryResponse> result = service.listPublic(1, 20);

        assertThat(result.getTotal()).isZero();
        assertThat(result.getRecords()).isEmpty();
        verify(mapper, never()).findByUuids(anyList());
        verifyNoInteractions(userFacade);
    }

    @Test
    @DisplayName("listPublic：沒有任何 series 時回空頁，不呼叫 article／user facade")
    void listPublicWithoutAnySeries_returnsEmptyPageWithoutFacadeCalls() {
        when(mapper.findAllUuidsOrderByCreatedAtDesc()).thenReturn(List.of());

        PageResult<SeriesSummaryResponse> result = service.listPublic(1, 20);

        assertThat(result.getTotal()).isZero();
        verifyNoInteractions(articleFacade, userFacade);
    }

    @Test
    @DisplayName("listPublic：超出範圍的頁回空清單，但 total 不變")
    void listPublicOutOfRangePageReturnsEmptyListButTotalUnchanged() {
        UUID s10 = UUID.randomUUID();
        when(mapper.findAllUuidsOrderByCreatedAtDesc()).thenReturn(List.of(s10));
        when(articleFacade.countPublishedBySeriesUuids(List.of(s10))).thenReturn(Map.of(s10, 1));

        PageResult<SeriesSummaryResponse> result = service.listPublic(5, 20);

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getRecords()).isEmpty();
    }

    /**
     * 鎖住「newest first」的排序契約。
     *
     * <p>{@code SeriesMapper.findByUuids} 的回傳順序不保證，listPublic 之所以仍能保證「最新優先」，
     * 全靠依 pageUuids 走訪、以 map 查表重組順序。本測試刻意讓 mapper 回傳與 pageUuids 相反的順序。</p>
     */
    @Test
    @DisplayName("listPublic：回傳順序鎖定在 pageUuids，即使 mapper 回傳順序相反也不受影響")
    void listPublicOrdersRecordsByPageUuidsRegardlessOfMapperReturnOrder() {
        UUID s10 = UUID.randomUUID();
        UUID s20 = UUID.randomUUID();
        UUID s30 = UUID.randomUUID();
        when(mapper.findAllUuidsOrderByCreatedAtDesc()).thenReturn(List.of(s10, s20, s30));
        when(articleFacade.countPublishedBySeriesUuids(List.of(s10, s20, s30)))
                .thenReturn(Map.of(s10, 1, s20, 1, s30, 1));
        UUID author = UUID.randomUUID();
        when(mapper.findByUuids(List.of(s10, s20, s30))).thenReturn(List.of(
                listRow(s30, "s30", author), listRow(s20, "s20", author), listRow(s10, "s10", author)));
        when(userFacade.getDisplayInfoByUuids(anyCollection())).thenReturn(Map.of());

        PageResult<SeriesSummaryResponse> result = service.listPublic(1, 20);

        assertThat(result.getRecords())
                .extracting(SeriesSummaryResponse::getSlug)
                .containsExactly("s10", "s20", "s30");
    }

    /**
     * 鎖住 offset 運算不溢位：{@code (page - 1) * size} 若以 int 相乘會溢位成負數，被 {@code Math.max(0, …)}
     * 吞成 0 而誤回第 1 頁。long 版本讓 offset 落在範圍外，直接回空清單且不查明細。
     */
    @Test
    @DisplayName("listPublic：給超大 page 時 offset 以 long 運算不溢位，回空清單而非誤回第一頁")
    void listPublicHugePageDoesNotOverflowOffsetArithmetic() {
        UUID s10 = UUID.randomUUID();
        UUID s20 = UUID.randomUUID();
        when(mapper.findAllUuidsOrderByCreatedAtDesc()).thenReturn(List.of(s10, s20));
        when(articleFacade.countPublishedBySeriesUuids(List.of(s10, s20))).thenReturn(Map.of(s10, 1, s20, 1));
        lenient().when(mapper.findByUuids(List.of(s10, s20)))
                .thenReturn(List.of(listRow(s10, "s10", UUID.randomUUID()), listRow(s20, "s20", UUID.randomUUID())));

        PageResult<SeriesSummaryResponse> result = service.listPublic(30_000_000, 100);

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getRecords()).isEmpty();
        verify(mapper, never()).findByUuids(anyList());
    }

    @Test
    @DisplayName("listPublic：size 夾在 [1, 100]，page 最小為 1")
    void listPublicClampsPageAndSize() {
        when(mapper.findAllUuidsOrderByCreatedAtDesc()).thenReturn(List.of());

        PageResult<SeriesSummaryResponse> result = service.listPublic(-3, 100_000);

        assertThat(result.getCurrent()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(100);
        verify(mapper, times(1)).findAllUuidsOrderByCreatedAtDesc();
        verify(articleFacade, never()).countPublishedBySeriesUuids(any());
    }
}
