package dowob.xyz.blog.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 排程池設定測試（findings PERF-16）。
 *
 * <p>Spring Boot 預設排程池只有 1 條執行緒，既有的 flush／trending job 與新增的每日孤兒校正 job
 * 會互相等待。此測試以真實的 {@code application.yaml} 載入排程自動設定，驗證實際產生的
 * {@link ThreadPoolTaskScheduler} 大小，而不只是檢查字串。不需要 Docker。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("排程池設定")
class SchedulingPoolConfigTest {

    /** 啟用排程，讓 {@link TaskSchedulingAutoConfiguration} 建立 taskScheduler。 */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingEnabled {
    }

    @Test
    @DisplayName("application.yaml 將排程池設為 4 條執行緒")
    void applicationYaml_configuresSchedulerPoolSizeOfFour() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(SchedulingEnabled.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);
                    assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
                });
    }
}
