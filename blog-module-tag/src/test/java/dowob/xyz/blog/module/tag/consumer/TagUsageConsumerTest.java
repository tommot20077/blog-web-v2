package dowob.xyz.blog.module.tag.consumer;

import dowob.xyz.blog.module.tag.event.ArticleTagEvent;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.repository.TagRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TagUsageConsumer unit tests
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TagUsageConsumer unit tests")
class TagUsageConsumerTest {

    @Mock
    private TagRepository tagRepository;

    @Mock
    private RedisTemplate<String, String> stringRedisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOps;

    @InjectMocks
    private TagUsageConsumer tagUsageConsumer;

    @Test
    @DisplayName("handleArticleTagged: known tagIds increment usageCount")
    void handleArticlePublished_withTagIds_incrementsUsageCount() {
        UUID tagId = UUID.randomUUID();
        Tag tag = new Tag();
        tag.setId(tagId);
        tag.setUsageCount(0);
        when(tagRepository.findById(tagId)).thenReturn(Optional.of(tag));
        when(tagRepository.save(any(Tag.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);

        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), List.of(tagId));
        tagUsageConsumer.handleArticleTagged(event);

        assertThat(tag.getUsageCount()).isEqualTo(1);
        verify(tagRepository).save(tag);
        verify(zSetOps).incrementScore(eq("tag:hot"), eq(tagId.toString()), eq(1.0));
    }

    @Test
    @DisplayName("handleArticleTagged: unknown tagId is silently skipped")
    void handleArticlePublished_withUnknownTagId_skips() {
        UUID unknownId = UUID.randomUUID();
        when(tagRepository.findById(unknownId)).thenReturn(Optional.empty());

        ArticleTagEvent event = new ArticleTagEvent(UUID.randomUUID(), List.of(unknownId));
        tagUsageConsumer.handleArticleTagged(event);

        verify(tagRepository, never()).save(any());
    }
}
