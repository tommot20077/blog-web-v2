package dowob.xyz.blog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * 部落格系統啟動類
 *
 * @author Yuan
 * @version 1.0
 */
@EnableAspectJAutoProxy
@SpringBootApplication
public class BlogWebV2Application {
    /**
     * 啟動方法
     *
     * @param args 命令行參數
     */
    public static void main(String[] args) {
        SpringApplication.run(BlogWebV2Application.class, args);
    }
}
