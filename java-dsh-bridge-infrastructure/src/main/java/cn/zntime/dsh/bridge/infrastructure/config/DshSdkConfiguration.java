package cn.zntime.dsh.bridge.infrastructure.config;

import cn.zntime.dsh.bridge.sdk.DshSdk;
import cn.zntime.dsh.bridge.sdk.DshSdkConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static java.lang.System.Logger.Level.ERROR;

/** 装配在首次请求时启动的 DSH SDK。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DshProperties.class)
public class DshSdkConfiguration {
    private static final System.Logger LOG = System.getLogger(DshSdkConfiguration.class.getName());

    /** DSH 源码工程构建后的 CLI 入口，相对于工程根目录。 */
    private static final Path CLI_ENTRY = Path.of("apps", "cli", "lib", "bin.js");

    /**
     * 创建 SDK Bean，并在应用关闭时回收 DSH。
     *
     * @param properties 应用提供的配置
     * @param appPath 通过 dsh.app.path 提供的可选 DSH 工程根目录
     * @param executable 通过 DSH_EXECUTABLE 提供的可选可执行文件
     * @return 可供多个请求共享的 SDK
     */
    @Bean(destroyMethod = "close")
    public DshSdk dshSdk(DshProperties properties, @Value("${dsh.app.path:}") String appPath,
                         @Value("${DSH_EXECUTABLE:}") String executable) {
        Path home = Optional.ofNullable(properties.dshHome()).filter(value -> !value.isBlank()).map(Path::of).orElse(null);
        List<Path> patches = Optional.ofNullable(properties.patchFiles()).orElseGet(List::of)
                .stream().map(Path::of).toList();
        List<String> command = resolveCommand(appPath, properties.command(), executable);
        return new DshSdk(new DshSdkConfig(command, Path.of(properties.workingDirectory()), home,
                properties.profile(), patches, properties.provider(), properties.model(),
                properties.initializeTimeout(), properties.runTimeout()));
    }

    /**
     * 优先使用显式配置的 DSH 工程根目录、完整命令或可执行文件，最后交由 PATH 解析。
     *
     * @param appPath DSH 工程根目录，忽略首尾空白，相对路径以应用启动目录为基准
     * @param configured DSH 命令及前置参数，未配置时为空
     * @param executable DSH_EXECUTABLE 的值，未配置时为空字符串
     * @return 启动 DSH 的命令及前置参数
     * @throws InvalidPathException 显式配置的工程路径格式无效时抛出
     */
    private static List<String> resolveCommand(String appPath, List<String> configured, String executable) {
        String rootDirectory = appPath.strip();
        if (!rootDirectory.isEmpty()) {
            return List.of("node", checkAppPath(rootDirectory).toString());
        }
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        if (!executable.isBlank()) {
            return List.of(executable);
        }

        return List.of("dsh");
    }

    /**
     * 在应用启动时检查 DSH 工程目录和构建产物，并解析出 CLI 的绝对路径。
     *
     * @param appPath 配置的 DSH 工程根目录
     * @return 预期的 CLI 路径
     * @throws InvalidPathException 配置值不符合文件系统路径格式时抛出
     */
    private static Path checkAppPath(String appPath) {
        Path root;
        try {
            root = Path.of(appPath).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            LOG.log(ERROR, "Invalid dsh.app.path: {0}. Set it to a valid DeepSeek Harness project directory.",
                    e.getReason());
            throw e;
        }
        Path entry = root.resolve(CLI_ENTRY);
        if (!Files.isDirectory(root)) {
            LOG.log(ERROR, "Invalid dsh.app.path: {0} is not an accessible directory. "
                    + "Set it to the DeepSeek Harness project root. Expected CLI: {1}", root, entry);
        } else if (!Files.isRegularFile(entry) || !Files.isReadable(entry)) {
            LOG.log(ERROR, "Invalid dsh.app.path: the CLI entry {0} is missing or unreadable. "
                    + "Build DeepSeek Harness or set dsh.app.path to a built checkout.", entry);
        }
        return entry;
    }
}
