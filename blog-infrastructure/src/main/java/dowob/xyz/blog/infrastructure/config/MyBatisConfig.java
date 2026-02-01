package dowob.xyz.blog.infrastructure.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis 配置
 * 
 * <p>
 * 啟用 Mapper 掃描，使用 Spring Boot 自動配置
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
@MapperScan(basePackages = {
        "dowob.xyz.blog.module.user.mapper",
        "dowob.xyz.blog.module.article.mapper",
        "dowob.xyz.blog.module.tag.mapper",
        "dowob.xyz.blog.module.file.mapper"
})
public class MyBatisConfig {
    // 使用 Spring Boot 自動配置，不需要手動建立 SqlSessionFactory
    // 若需要自定義設定，可在 application.yaml 中配置 mybatis.* 屬性
}
