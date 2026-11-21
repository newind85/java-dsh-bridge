package cn.zntime.dsh.bridge.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * 从应用配置绑定 DSH 进程和会话参数。
 *
 * @param command 可选的可执行文件和前置参数
 * @param workingDirectory DSH 工作目录
 * @param dshHome 可选的 DSH 数据目录
 * @param profile DSH SDK profile
 * @param patchFiles 可选的启动 patch 文件
 * @param provider 模型供应商
 * @param model 模型名称
 * @param initializeTimeout 初始化握手期限
 * @param runTimeout 单次执行等待期限
 */
@ConfigurationProperties(prefix = "dsh.bridge")
public record DshProperties(
        List<String> command,
        @DefaultValue(".") String workingDirectory,
        String dshHome,
        @DefaultValue("sdk") String profile,
        List<String> patchFiles,
        @DefaultValue("deepseek-official") String provider,
        @DefaultValue("deepseek-v4-flash") String model,
        @DefaultValue("30s") Duration initializeTimeout,
        @DefaultValue("5m") Duration runTimeout
) {
}
