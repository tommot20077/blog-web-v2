package dowob.xyz.blog;

import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context Smoke Test —— 只驗證「Spring Context 組得起來」本身。
 *
 * <h2>動機</h2>
 * <p>article↔file 模組的建構子循環依賴曾躲過 6 個任務、2 輪安全複審與 400+ 綠燈單元測試，
 * 只有真的啟動應用程式才現形（見 {@code fix(infrastructure): 拆出 ArticleLookupFacade
 * 打斷 article 與 file 模組循環依賴}）。單元測試普遍用 {@code @Mock}/{@code @MockBean}
 * 隔開協作者，而循環依賴、Bean 缺失、設定錯誤這類「bean 組裝期」問題只有 Spring 真的
 * refresh 整個 {@link ApplicationContext} 時才會爆——本測試就是補這道防線：只斷言 context
 * 能成功啟動、核心 Bean 能被注入，不驗證業務邏輯（業務邏輯已由既有單元測試 / IT / E2E 覆蓋）。
 *
 * <h2>外部依賴策略（為何直接繼承 {@link AbstractE2ETest}）</h2>
 * <p>專案既有的 {@code -Pe2e} profile（見 {@link dowob.xyz.blog.e2e.SmokeE2E}）已證明
 * 「PostgreSQL + Redis + RabbitMQ + MinIO + Elasticsearch 五個真容器 + 完整
 * {@code BlogWebV2Application}」是啟動全模組 context 最穩定的組合（曾踩過並修好 RabbitMQ
 * vhost 等坑）。13 個模組中有 7 個各自宣告 {@code RabbitMqConfig}（Queue/Exchange/Binding
 * Bean，RabbitAdmin 在 {@code ContextRefreshedEvent} 時會 auto-declare），若把
 * RabbitMQ/Elasticsearch/MinIO mock 掉或用屬性關閉，這些模組的組裝路徑就測不到——而這正是
 * 「mock 越多、漏測越多」的風險，與本測試存在的目的相違背。因此選擇重用已驗證過的
 * {@link AbstractE2ETest} 容器設定，只是換一個獨立的啟用開關（{@code context.smoke}
 * system property），不與 {@code -Pe2e} 的完整測試套件綁在一起。
 * <p>唯一例外是 Mail（SMTP）：{@code application-e2e.yaml} 已將
 * {@code app.mail.consumer-enabled} 設為 {@code false}，且 {@code JavaMailSender} bean
 * 的建構本身不需要真的連上 SMTP（連線是發信當下才建立），因此不需要額外容器或 mock，
 * 屬性關閉即可，不影響 bean 組裝路徑的覆蓋率。
 *
 * <h2>取捨</h2>
 * <p>本測試刻意不追求「輕量、可每次 commit 都跑」——它與 {@code -Pe2e} 套件一樣需要
 * Docker Desktop + 5 個真容器，成本相近。它的價值在於：不需要記住 {@code -Pe2e} 這個
 * profile、不需要等 67 個 E2E 案例跑完，只用一個 system property 就能單獨驗證「context
 * 組不組得起來」這一件事，降低「忘記跑」的門檻。若要進一步降低「忘記跑」的風險（例如
 * 納入 pre-push hook 或 CI 必跑步驟），需要 Yuan 決策，不在本測試範圍內。
 *
 * <h2>Gate 設計</h2>
 * <p>預設 {@code mvn test}（不帶任何參數）<b>不會執行</b>本測試的斷言：
 * {@link EnabledIfSystemProperty} 在類別層級關閉整個測試類別；JUnit 5.4+ 對停用的類別
 * 不會呼叫其 {@code @BeforeAll}，因此 {@link AbstractE2ETest} 內啟動 5 個容器的
 * {@code static} 區塊也不會被觸發——gate 關閉時完全不碰 Docker，測試回報為 skipped。
 *
 * <p><b>啟用方式：</b>
 * <pre>{@code
 * mvn test -pl blog-start -Dcontext.smoke=true -Dtest=ContextSmokeTest
 * }</pre>
 * 需要本機 Docker Desktop 正在執行（Testcontainers 依賴），首次執行需要拉取 5 個 image，
 * 耗時較久。
 */
@EnabledIfSystemProperty(named = "context.smoke", matches = "true")
@DisplayName("Context Smoke Test（gate: -Dcontext.smoke=true）")
class ContextSmokeTest extends AbstractE2ETest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("完整應用程式 Spring Context 組裝成功（13 個模組 + 5 個外部依賴 Testcontainers）")
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.getBeanDefinitionCount()).isGreaterThan(0);
    }
}
