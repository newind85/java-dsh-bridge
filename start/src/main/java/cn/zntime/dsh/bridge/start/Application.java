package cn.zntime.dsh.bridge.start;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** java-dsh-bridge 的 Spring Boot 启动入口。 */
@SpringBootApplication(scanBasePackages = "cn.zntime.dsh.bridge")
public class Application {
    /**
     * 启动 HTTP 服务和应用上下文。
     *
     * @param args Spring Boot 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
