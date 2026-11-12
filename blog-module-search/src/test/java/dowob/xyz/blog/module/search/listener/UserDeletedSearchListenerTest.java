package dowob.xyz.blog.module.search.listener;

import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.search.service.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link UserDeletedSearchListener} 單元測試（DATA-02）。
 *
 * <p>ES 文件冗餘存了 {@code author.nickname}，搜尋結果直接回傳它；使用者刪帳匿名化後，已發布文章在索引內的作者暱稱
 * 必須同步改成匿名暱稱，否則刪帳後搜尋仍會洩漏原暱稱。匿名暱稱的來源是 {@link UserFacade}（users 列已 commit 匿名化），
 * 事件本身不帶任何個資。{@link ConsumerExecutor} 使用真實實例。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserDeletedSearchListener 單元測試")
class UserDeletedSearchListenerTest {

    @Mock private SearchService searchService;
    @Mock private UserFacade userFacade;

    private UserDeletedSearchListener listener;

    @BeforeEach
    void setUp() {
        listener = new UserDeletedSearchListener(searchService, userFacade, new ConsumerExecutor());
    }

    private static UserDeletedEvent event(UUID userUuid) {
        return new UserDeletedEvent(UUID.randomUUID(), userUuid, Instant.now());
    }

    private void stubDisplayName(UUID userUuid, String nickname) {
        when(userFacade.getDisplayInfoByUuids(any(Collection.class)))
                .thenReturn(Map.of(userUuid, new UserDisplayInfo(userUuid, nickname, null)));
    }

    @Test
    @DisplayName("收到事件：以 UserFacade 取得匿名暱稱，改寫該作者所有索引文件的 author.nickname，並清除搜尋歷史")
    void onUserDeleted_rewritesAuthorNicknameAndClearsHistory() throws Exception {
        UUID userUuid = UUID.randomUUID();
        stubDisplayName(userUuid, "已刪除使用者#abcd1234");

        listener.onUserDeleted(event(userUuid));

        InOrder order = inOrder(userFacade, searchService);
        order.verify(userFacade).getDisplayInfoByUuids(List.of(userUuid));
        order.verify(searchService).updateAuthorNickname(eq(userUuid), eq("已刪除使用者#abcd1234"), any());
        order.verify(searchService).clearHistory(userUuid);
    }

    @Test
    @DisplayName("UserFacade 查不到該使用者：退回固定的「已刪除使用者」，仍須蓋掉索引內的原暱稱")
    void onUserDeleted_userMissingInFacade_fallsBackToGenericName() throws Exception {
        UUID userUuid = UUID.randomUUID();
        when(userFacade.getDisplayInfoByUuids(any(Collection.class))).thenReturn(Map.of());

        listener.onUserDeleted(event(userUuid));

        verify(searchService).updateAuthorNickname(eq(userUuid), eq("已刪除使用者"), any());
        verify(searchService).clearHistory(userUuid);
    }

    @Test
    @DisplayName("ES 更新失敗：例外往外拋（交由容器重試，耗盡進 DLQ），不吞掉")
    void onUserDeleted_elasticsearchFails_rethrows() throws Exception {
        UUID userUuid = UUID.randomUUID();
        stubDisplayName(userUuid, "已刪除使用者#abcd1234");
        doThrow(new IllegalStateException("es down")).when(searchService)
                .updateAuthorNickname(eq(userUuid), eq("已刪除使用者#abcd1234"), any());

        assertThatThrownBy(() -> listener.onUserDeleted(event(userUuid))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("缺 userUuid 的 payload：不查詢、不更新、仍正常返回（避免無限重送）")
    void onUserDeleted_nullUserUuid_doesNothing() throws Exception {
        listener.onUserDeleted(event(null));

        verifyNoInteractions(searchService, userFacade);
        verifyNoMoreInteractions(searchService);
    }
}
