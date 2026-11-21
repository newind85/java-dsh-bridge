package cn.zntime.dsh.bridge.sdk;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 启动 DSH SDK 进程和执行会话所需的配置。
 *
 * @param command 可执行文件与固定前置参数，例如 `["node", "/path/to/bin.js"]`
 * @param workingDirectory DSH 进程和新会话的默认工作目录
 * @param dshHome 可选的 DSH 数据目录，留空时沿用 DSH 的原生解析规则
 * @param profile 提供 SDK JSON-RPC 服务的 DSH profile
 * @param patchFiles 按顺序加载的 DSH patch 文件
 * @param provider 模型供应商
 * @param model 模型名称
 * @param initializeTimeout SDK 初始化握手的等待期限
 * @param runTimeout 单次会话等待期限，零表示一直等待
 */
public record DshSdkConfig(
        List<String> command,
        Path workingDirectory,
        Path dshHome,
        String profile,
        List<Path> patchFiles,
        String provider,
        String model,
        Duration initializeTimeout,
        Duration runTimeout
) {
    /**
     * 校验启动参数并固定目录和 patch 路径。
     */
    public DshSdkConfig {
        command = List.copyOf(command);
        if (command.isEmpty() || command.getFirst().isBlank()) {
            throw new IllegalArgumentException("DSH command must include an executable");
        }
        workingDirectory = workingDirectory.toAbsolutePath().normalize();
        if (dshHome != null) {
            dshHome = dshHome.toAbsolutePath().normalize();
        }
        patchFiles = patchFiles.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        if (profile.isBlank() || profile.startsWith("-") || provider.isBlank() || model.isBlank()) {
            throw new IllegalArgumentException("DSH profile, provider and model must be valid");
        }
        Objects.requireNonNull(initializeTimeout, "initializeTimeout");
        Objects.requireNonNull(runTimeout, "runTimeout");
        if (initializeTimeout.isZero() || initializeTimeout.isNegative() || runTimeout.isNegative()) {
            throw new IllegalArgumentException("Initialize timeout must be positive and run timeout non-negative");
        }
        initializeTimeout.toNanos();
        runTimeout.toNanos();
    }

    /**
     * 返回使用 PATH 上的 DSH、当前目录及默认模型路由的配置。
     *
     * @return 默认 SDK 配置
     */
    public static DshSdkConfig defaults() {
        return new DshSdkConfig(List.of("dsh"), Path.of("."), null, "sdk", List.of(),
                "deepseek-official", "deepseek-v4-flash", Duration.ofSeconds(30), Duration.ZERO);
    }
}
