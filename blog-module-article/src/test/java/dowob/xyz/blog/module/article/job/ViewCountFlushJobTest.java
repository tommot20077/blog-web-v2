package dowob.xyz.blog.module.article.job;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dowob.xyz.blog.module.article.service.ViewCountService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
@DisplayName("ViewCountFlushJob 單元測試")
class ViewCountFlushJobTest {

    @Mock
    private ViewCountService viewCountService;

    @InjectMocks
    private ViewCountFlushJob viewCountFlushJob;

    @Test
    @DisplayName("flushViewCounts 應調用 viewCountService.flushViewCounts() 一次")
    void flushViewCounts_shouldInvokeServiceOnce() {
        viewCountFlushJob.flushViewCounts();

        verify(viewCountService, times(1)).flushViewCounts();
    }

    @Test
    @DisplayName("@Scheduled 註解應設定 fixedDelay=300000, initialDelay=60000")
    void flushViewCounts_shouldHaveCorrectScheduledAnnotation() throws NoSuchMethodException {
        Method method = ViewCountFlushJob.class.getMethod("flushViewCounts");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertNotNull(scheduled, "@Scheduled 註解應存在於 flushViewCounts 方法上");
        assertEquals(300_000, scheduled.fixedDelay(), "fixedDelay 應為 300000 ms");
        assertEquals(60_000, scheduled.initialDelay(), "initialDelay 應為 60000 ms");
    }

    @Test
    @DisplayName("連續調用兩次應調用 service 兩次")
    void flushViewCounts_calledTwice_shouldInvokeServiceTwice() {
        viewCountFlushJob.flushViewCounts();
        viewCountFlushJob.flushViewCounts();

        verify(viewCountService, times(2)).flushViewCounts();
    }

    @Test
    @DisplayName("service 拋出異常時應向上傳播")
    void flushViewCounts_whenServiceThrows_shouldPropagateException() {
        doThrow(new RuntimeException("flush failed"))
                .when(viewCountService).flushViewCounts();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> viewCountFlushJob.flushViewCounts());

        assertEquals("flush failed", ex.getMessage());
        verify(viewCountService, times(1)).flushViewCounts();
    }
}
