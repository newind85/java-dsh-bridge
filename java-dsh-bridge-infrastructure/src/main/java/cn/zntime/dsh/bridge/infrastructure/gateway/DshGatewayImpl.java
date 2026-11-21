package cn.zntime.dsh.bridge.infrastructure.gateway;

import cn.zntime.dsh.bridge.client.api.DshRunException;
import cn.zntime.dsh.bridge.client.dto.co.DshRunEvent;
import cn.zntime.dsh.bridge.client.dto.co.DshRunResponse;
import cn.zntime.dsh.bridge.domain.gateway.DshGateway;
import cn.zntime.dsh.bridge.sdk.DshRunResult;
import cn.zntime.dsh.bridge.sdk.DshSdk;
import cn.zntime.dsh.bridge.sdk.DshSdkException;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;

/** 通过可独立使用的 Java SDK 调用 DSH。 */
@Component
public class DshGatewayImpl implements DshGateway {
    private static final System.Logger LOG = System.getLogger(DshGatewayImpl.class.getName());

    private final DshSdk sdk;

    /**
     * 注入由应用管理生命周期的 SDK 实例。
     *
     * @param sdk DSH 客户端
     */
    public DshGatewayImpl(DshSdk sdk) {
        this.sdk = sdk;
    }

    /** {@inheritDoc} */
    @Override
    public DshRunResponse run(String text, String sessionId, Consumer<DshRunEvent> observer) throws InterruptedException {
        try {
            DshRunResult result = sdk.runStreaming(text, sessionId,
                    event -> observer.accept(new DshRunEvent(event.method(), event.paramsJson())));
            return new DshRunResponse(result.sessionId(), result.text(), result.finishReason());
        } catch (DshSdkException e) {
            if (e.isStartupFailure()) {
                throw startupFailure(e);
            }
            LOG.log(WARNING, "DSH run failed, kind={0}", e.kind());
            throw switch (e.kind()) {
                case TIMEOUT -> new DshRunException(DshRunException.Kind.TIMEOUT, "DSH execution timed out", e);
                case INVALID_SESSION_ID -> new DshRunException(DshRunException.Kind.INVALID_SESSION_ID,
                        "Use the sessionId returned by a previous run, or omit it to start a new session.", e);
                case SESSION_NOT_FOUND -> new DshRunException(DshRunException.Kind.SESSION_NOT_FOUND,
                        "The DSH session was not found. Check the sessionId and the DSH data directory.", e);
                case SESSION_BUSY -> new DshRunException(DshRunException.Kind.SESSION_BUSY,
                        "A run is already active for this session. Wait for it to finish before sending another turn.", e);
                default -> new DshRunException(DshRunException.Kind.UPSTREAM_ERROR, "DSH execution failed", e);
            };
        }
    }

    /**
     * 根据启动失败的类别记录诊断提示，并生成可由 HTTP 和 SSE 返回的错误。
     *
     * @param error 启动进程或握手时抛出的 SDK 异常
     * @return 包含故障阶段与排查指引的执行异常
     */
    private static DshRunException startupFailure(DshSdkException error) {
        DshRunException.Kind kind = error.kind() == DshSdkException.Kind.TIMEOUT
                ? DshRunException.Kind.STARTUP_TIMEOUT : DshRunException.Kind.STARTUP_ERROR;
        String message = switch (error.kind()) {
            case LAUNCH -> "Could not launch the DSH process. Check Node on PATH, dsh.app.path, "
                    + "dsh.bridge.command, DSH_EXECUTABLE and dsh.bridge.working-directory.";
            case TIMEOUT -> "DSH SDK initialize timed out. Check whether the CLI can start with the configured "
                    + "profile and whether dsh.bridge.initialize-timeout is sufficient.";
            case TRANSPORT -> "The DSH connection closed before SDK initialization completed. Check Node, "
                    + "the built CLI at dsh.app.path/apps/cli/lib/bin.js and the configured profile.";
            case PROTOCOL -> "DSH returned an invalid SDK initialize response. Check the CLI version "
                    + "and use a profile that provides the DSH SDK service.";
            case REMOTE -> "DSH rejected SDK initialization. Check the configured profile, provider, model and credentials.";
            default -> "DSH SDK initialization failed. Check the DSH CLI and profile configuration.";
        };
        if (error.kind() == DshSdkException.Kind.LAUNCH && error.getCause() != null) {
            LOG.log(ERROR, "DSH startup failed (LAUNCH). {0} OS error: {1}", message, error.getCause().getMessage());
        } else {
            LOG.log(ERROR, "DSH startup failed ({0}). {1}", error.kind(), message);
        }
        return new DshRunException(kind, message, error);
    }
}
