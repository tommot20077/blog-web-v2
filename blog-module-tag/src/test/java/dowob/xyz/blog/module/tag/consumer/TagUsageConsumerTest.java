package dowob.xyz.blog.module.tag.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.module.tag.event.ArticleTagEvent;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TagUsageConsumer unit tests
 *
 * @author Yuan
 * @version 1.0
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("TagUsageConsumer unit tests")
class TagUsageConsumerTest {

    @Mock
    private TagRepository tagRepository;

    @Mock
    private RedisTemplate<String, String> stringRedisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOps;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private Channel channel;

    @InjectMocks
    private TagUsageConsumer tagUsageConsumer;

    /** 預設：首次處理（markProcessed=true）；需要模擬重送的測試自行 override */
    @BeforeEach
    void setUpIdempotency() {
        when(idempotencyService.markProcessed(any(), eq(TagUsageConsumer.CONSUMER_NAME))).thenReturn(true);
    }

    @Test
    @DisplayName("handleArticleTagged: known tagIds increment usageCount and acks")
    void handleArticlePublished_withTagIds_incrementsUsageCount() throws IOException {
        UUID tagId = UUID.randomUUID();
        Tag tag = new Tag();
        tag.setId(tagId);
        tag.setUsageCount(0);
        when(tagRepository.findById(tagId)).thenReturn(Optional.of(tag));
        when(tagRepository.save(any(Tag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);

        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), UUID.randomUUID(), List.of(tagId));
        tagUsageConsumer.handleArticleTagged(event, channel, 10L);

        assertThat(tag.getUsageCount()).isEqualTo(1);
        verify(tagRepository).save(tag);
        verify(zSetOps).incrementScore(eq(RedisKeyConstant.TAG_HOT_KEY), eq(tagId.toString()), eq(1.0));
        verify(channel).basicAck(10L, false);
    }

    @Test
    @DisplayName("handleArticleTagged: unknown tagId is silently skipped and acks")
    void handleArticlePublished_withUnknownTagId_skips() throws IOException {
        UUID unknownId = UUID.randomUUID();
        when(tagRepository.findById(unknownId)).thenReturn(Optional.empty());

        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), UUID.randomUUID(), List.of(unknownId));
        tagUsageConsumer.handleArticleTagged(event, channel, 20L);

        verify(tagRepository, never()).save(any());
        verify(channel).basicAck(20L, false);
    }

    @Test
    @DisplayName("handleArticleTagged: 處理失敗時呼叫 basicNack")
    void handleArticleTagged_onFailure_callsBasicNack() throws IOException {
        UUID tagId = UUID.randomUUID();
        when(tagRepository.findById(tagId)).thenThrow(new RuntimeException("DB error"));

        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), UUID.randomUUID(), List.of(tagId));
        tagUsageConsumer.handleArticleTagged(event, channel, 30L);

        verify(channel).basicNack(30L, false, false);
    }

    /**
     * 情境：同一事件重送兩次（相同 eventId）→ 使用計數只增加一次。
     *
     * <p>usageCount 是非冪等 +1（且寫入 DB 為永久）；at-least-once 重送須以 eventId 去重,
     * 否則標籤使用數與 Redis 熱門分數會灌水。</p>
     */
    @Test
    @DisplayName("handleArticleTagged: 同一事件重送 → 使用計數只加一次（冪等）")
    void handleArticleTagged_duplicateEvent_incrementsOnlyOnce() throws IOException {
        UUID tagId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Tag tag = new Tag();
        tag.setId(tagId);
        tag.setUsageCount(0);
        when(tagRepository.findById(tagId)).thenReturn(Optional.of(tag));
        when(tagRepository.save(any(Tag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);
        // 首次 true、重送 false
        when(idempotencyService.markProcessed(eq(eventId), eq(TagUsageConsumer.CONSUMER_NAME)))
                .thenReturn(true, false);

        ArticleTagEvent event = new ArticleTagEvent(eventId, UUID.randomUUID(), List.of(tagId));
        tagUsageConsumer.handleArticleTagged(event, channel, 1L);
        tagUsageConsumer.handleArticleTagged(event, channel, 2L);

        assertThat(tag.getUsageCount()).isEqualTo(1);
        verify(tagRepository, times(1)).save(tag);
        verify(zSetOps, times(1)).incrementScore(eq(RedisKeyConstant.TAG_HOT_KEY), eq(tagId.toString()), eq(1.0));
        verify(channel).basicAck(1L, false);
        verify(channel).basicAck(2L, false);
    }

    /**
     * 情境：舊訊息（eventId 為 null）→ 照舊處理，不呼叫 idempotencyService。
     */
    @Test
    @DisplayName("handleArticleTagged: eventId 為 null（舊訊息）→ 照舊處理不做去重")
    void handleArticleTagged_nullEventId_processesWithoutIdempotency() throws IOException {
        UUID tagId = UUID.randomUUID();
        Tag tag = new Tag();
        tag.setId(tagId);
        tag.setUsageCount(0);
        when(tagRepository.findById(tagId)).thenReturn(Optional.of(tag));
        when(tagRepository.save(any(Tag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);

        ArticleTagEvent event = new ArticleTagEvent(null, UUID.randomUUID(), List.of(tagId));
        tagUsageConsumer.handleArticleTagged(event, channel, 7L);

        assertThat(tag.getUsageCount()).isEqualTo(1);
        verify(channel).basicAck(7L, false);
        verify(idempotencyService, never()).markProcessed(any(), any());
    }
}
