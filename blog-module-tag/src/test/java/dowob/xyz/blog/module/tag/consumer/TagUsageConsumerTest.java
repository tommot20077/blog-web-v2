package dowob.xyz.blog.module.tag.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.module.tag.event.ArticleTagEvent;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.TagRepository;
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
    private Channel channel;

    @InjectMocks
    private TagUsageConsumer tagUsageConsumer;

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

        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), List.of(tagId));
        tagUsageConsumer.handleArticleTagged(event, channel, 10L);

        assertThat(tag.getUsageCount()).isEqualTo(1);
        verify(tagRepository).save(tag);
        verify(zSetOps).incrementScore(eq("tag:hot"), eq(tagId.toString()), eq(1.0));
        verify(channel).basicAck(10L, false);
    }

    @Test
    @DisplayName("handleArticleTagged: unknown tagId is silently skipped and acks")
    void handleArticlePublished_withUnknownTagId_skips() throws IOException {
        UUID unknownId = UUID.randomUUID();
        when(tagRepository.findById(unknownId)).thenReturn(Optional.empty());

        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), List.of(unknownId));
        tagUsageConsumer.handleArticleTagged(event, channel, 20L);

        verify(tagRepository, never()).save(any());
        verify(channel).basicAck(20L, false);
    }

    @Test
    @DisplayName("handleArticleTagged: 處理失敗時呼叫 basicNack")
    void handleArticleTagged_onFailure_callsBasicNack() throws IOException {
        UUID tagId = UUID.randomUUID();
        when(tagRepository.findById(tagId)).thenThrow(new RuntimeException("DB error"));

        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), List.of(tagId));
        tagUsageConsumer.handleArticleTagged(event, channel, 30L);

        verify(channel).basicNack(30L, false, false);
    }
}
