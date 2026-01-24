package dowob.xyz.blog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy
@SpringBootApplication
public class BlogWebV2Application {
    public static void main(String[] args) {
        SpringApplication.run(BlogWebV2Application.class, args);
    }

}
