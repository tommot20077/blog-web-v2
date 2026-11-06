package dowob.xyz.blog.mq;

import dowob.xyz.blog.infrastructure.config.RabbitMqConfig;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.module.comment.config.CommentRabbitMqConfig;
import dowob.xyz.blog.module.file.config.FileRabbitMqConfig;
import dowob.xyz.blog.module.reading.config.ReadingRabbitMqConfig;
import dowob.xyz.blog.module.recommend.config.RecommendRabbitMqConfig;
import dowob.xyz.blog.module.search.config.SearchRabbitMqConfig;
import dowob.xyz.blog.module.series.config.SeriesRabbitMqConfig;
import dowob.xyz.blog.module.tag.config.TagRabbitMqConfig;
import dowob.xyz.blog.module.user.config.UserRabbitMqConfig;
import dowob.xyz.blog.module.version.config.VersionRabbitMqConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全站 RabbitMQ 拓撲快照守衛（ARCH-19／ARCH-06）。
 *
 * <p>載入<b>所有</b>模組的 {@code *RabbitMqConfig}（不連 broker——連線皆 lazy，不需要 Docker），把每個 queue 的
 * 參數（durable／exclusive／autoDelete／全部 arguments）、每個 exchange 的參數與每條 binding 逐一快照，與
 * <b>重構前</b>錄下的期望值比對。目的：把 DLQ 參數（{@code dlqArgs()} 複本收斂成共用 helper）與共用 exchange
 * 宣告（5 個模組各自 {@code new TopicExchange}）收斂到單一來源時，確保拓撲<b>逐一不變</b>——RabbitMQ 對既有
 * queue／exchange 的參數不一致會回 406 PRECONDITION_FAILED，使應用程式啟動失敗，因此任何參數漂移都必須在這裡被攔下。</p>
 *
 * <p>期望值是從 ARCH-19 重構<b>之前</b>的程式碼跑出來的快照（含 version 模組 DLQ 的 {@code x-message-ttl=600000}，
 * 是否移除待 ARCH-18 決策）。刻意新增、移除或改參數時，必須同步修改這份期望值並在 PR 說明：
 * queue 參數變動在既有部署上需要先刪 queue 再重建。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("全站 MQ 拓撲快照（ARCH-19／ARCH-06）")
class MqTopologySnapshotTest {

    private static AnnotationConfigApplicationContext context;

    /** 只載入各模組的 RabbitMQ 拓撲設定，連線工廠為 lazy 的 CachingConnectionFactory（不會真的連線） */
    @Configuration
    @Import({RabbitMqConfig.class, ArticleRabbitMqConfig.class, CommentRabbitMqConfig.class,
            ReadingRabbitMqConfig.class, RecommendRabbitMqConfig.class, SeriesRabbitMqConfig.class,
            TagRabbitMqConfig.class, VersionRabbitMqConfig.class, SearchRabbitMqConfig.class,
            FileRabbitMqConfig.class, UserRabbitMqConfig.class})
    static class Topology {

        @Bean
        ConnectionFactory connectionFactory() {
            return new CachingConnectionFactory("localhost");
        }

        @Bean
        SimpleRabbitListenerContainerFactoryConfigurer simpleRabbitListenerContainerFactoryConfigurer() {
            return new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties());
        }
    }

    @BeforeAll
    static void startContext() {
        context = new AnnotationConfigApplicationContext(Topology.class);
    }

    @AfterAll
    static void stopContext() {
        if (context != null) {
            context.close();
        }
    }

    private static String describeQueue(Queue queue) {
        return "Q " + queue.getName() + " | durable=" + queue.isDurable() + " exclusive=" + queue.isExclusive()
                + " autoDelete=" + queue.isAutoDelete() + " | args=" + new TreeMap<>(queue.getArguments());
    }

    private static String describeExchange(Exchange exchange) {
        return "X " + exchange.getName() + " | type=" + exchange.getType() + " durable=" + exchange.isDurable()
                + " autoDelete=" + exchange.isAutoDelete() + " internal=" + exchange.isInternal()
                + " delayed=" + exchange.isDelayed() + " | args=" + new TreeMap<>(exchange.getArguments());
    }

    private static String describeBinding(Binding binding) {
        return "B " + binding.getExchange() + " -> " + binding.getDestinationType() + ":" + binding.getDestination()
                + " [" + binding.getRoutingKey() + "] args=" + new TreeMap<>(binding.getArguments());
    }

    @Test
    @DisplayName("每個 queue 的名稱與全部參數（含 DLQ 的 x-dead-letter-*、version 的 x-message-ttl）與重構前快照完全相同")
    void queues_matchSnapshot() {
        List<String> actual = context.getBeansOfType(Queue.class).values().stream()
                .map(MqTopologySnapshotTest::describeQueue).sorted().toList();

        assertThat(actual).containsExactlyElementsOf(MqTopologySnapshotData.QUEUES);
    }

    @Test
    @DisplayName("每個 exchange（以名稱去重）的型別、durable、autoDelete 與參數與重構前快照完全相同")
    void exchanges_matchSnapshot() {
        List<String> actual = context.getBeansOfType(Exchange.class).values().stream()
                .map(MqTopologySnapshotTest::describeExchange).collect(Collectors.toCollection(TreeSet::new))
                .stream().toList();

        assertThat(actual).containsExactlyElementsOf(MqTopologySnapshotData.EXCHANGES);
    }

    @Test
    @DisplayName("每條 binding（exchange → queue［routing key］）與重構前快照完全相同")
    void bindings_matchSnapshot() {
        List<String> actual = context.getBeansOfType(Binding.class).values().stream()
                .map(MqTopologySnapshotTest::describeBinding).sorted().toList();

        assertThat(actual).containsExactlyElementsOf(MqTopologySnapshotData.BINDINGS);
    }

    @Test
    @DisplayName("ARCH-06：全 context 只有一個名為 article.events 的 Exchange bean，且為 durable、非 autoDelete 的 topic")
    void articleEventsExchange_isDeclaredExactlyOnce() {
        Map<String, Exchange> beans = context.getBeansOfType(Exchange.class);

        List<Map.Entry<String, Exchange>> articleEvents = beans.entrySet().stream()
                .filter(e -> "article.events".equals(e.getValue().getName())).toList();

        assertThat(articleEvents).as("名為 article.events 的 Exchange bean：%s", articleEvents.stream().map(Map.Entry::getKey).toList())
                .hasSize(1);
        Exchange exchange = articleEvents.get(0).getValue();
        assertThat(exchange.getType()).isEqualTo("topic");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
        assertThat(exchange.isInternal()).isFalse();
        assertThat(exchange.getArguments()).isEmpty();
    }
}
