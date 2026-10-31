package dowob.xyz.blog.infrastructure.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.retry.RetryContext;
import org.springframework.retry.context.RetryContextSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link BoundedRetryContextCache} 的單元測試：超量時淘汰最舊的，而不是像 {@code MapRetryContextCache} 一樣拋例外。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("BoundedRetryContextCache：有界、超量淘汰最舊")
class BoundedRetryContextCacheTest {

    private static RetryContext context() {
        return new RetryContextSupport(null);
    }

    @Test
    @DisplayName("put／get／containsKey／remove 基本行為")
    void basicOperations() {
        BoundedRetryContextCache cache = new BoundedRetryContextCache(3);
        RetryContext ctx = context();

        cache.put("a", ctx);

        assertThat(cache.containsKey("a")).isTrue();
        assertThat(cache.get("a")).isSameAs(ctx);
        cache.remove("a");
        assertThat(cache.containsKey("a")).isFalse();
        assertThat(cache.get("a")).isNull();
    }

    @Test
    @DisplayName("超過容量時不拋例外，淘汰最舊的 key")
    void overCapacity_evictsOldestWithoutThrowing() {
        BoundedRetryContextCache cache = new BoundedRetryContextCache(2);

        assertThatCode(() -> {
            cache.put("a", context());
            cache.put("b", context());
            cache.put("c", context());
        }).doesNotThrowAnyException();

        assertThat(cache.containsKey("a")).as("最舊的被淘汰").isFalse();
        assertThat(cache.containsKey("b")).isTrue();
        assertThat(cache.containsKey("c")).isTrue();
    }
}
