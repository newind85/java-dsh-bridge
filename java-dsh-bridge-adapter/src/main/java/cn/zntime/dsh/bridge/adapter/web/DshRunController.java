package cn.zntime.dsh.bridge.adapter.web;

import cn.zntime.dsh.bridge.client.api.DshRunException;
import cn.zntime.dsh.bridge.client.api.DshRunServiceI;
import cn.zntime.dsh.bridge.client.dto.cmd.DshRunCmd;
import cn.zntime.dsh.bridge.client.dto.co.DshRunResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

import static java.lang.System.Logger.Level.DEBUG;

/** 提供阻塞式和 SSE 两种 DSH 执行入口。 */
@RestController
public class DshRunController {
    private static final System.Logger LOG = System.getLogger(DshRunController.class.getName());

    private final DshRunServiceI service;
    private final AsyncTaskExecutor executor;
    private final Duration heartbeatInterval;

    /**
     * 注入应用服务和用于 SSE 的应用执行器。
     *
     * @param service DSH 应用服务
     * @param executor Spring 管理的执行器
     * @param heartbeatInterval SSE 心跳间隔，零表示关闭心跳
     */
    public DshRunController(DshRunServiceI service, @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor,
                            @Value("${dsh.bridge.sse-heartbeat-interval:15s}") String heartbeatInterval) {
        this.service = service;
        this.executor = executor;
        this.heartbeatInterval = DurationStyle.detectAndParse(heartbeatInterval);
        if (this.heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("dsh.bridge.sse-heartbeat-interval must be non-negative");
        }
    }

    /**
     * 在请求线程等待 DSH 执行完成，将最终结果作为 JSON 返回。
     *
     * @param command 用户输入
     * @return 最终执行结果
     * @throws InterruptedException 等待时被中断
     */
    @PostMapping(path = "/run", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public DshRunResponse run(@Valid @RequestBody DshRunCmd command) throws InterruptedException {
        return service.run(command);
    }

    /**
     * 在后台运行 DSH，通过原 HTTP 连接持续推送事件和最终结果。
     *
     * @param command 用户输入
     * @return SSE 发射器
     */
    @PostMapping(path = "/run/events", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runEvents(@Valid @RequestBody DshRunCmd command) {
        DshSseEmitter emitter = new DshSseEmitter();
        FutureTask<Void> run = new FutureTask<>(() -> {
            stream(command, emitter);
            return null;
        });
        FutureTask<Void> heartbeat = new FutureTask<>(() -> {
            sendHeartbeats(emitter, run);
            return null;
        });
        Runnable cancel = () -> {
            heartbeat.cancel(true);
            run.cancel(true);
        };
        emitter.onCompletion(cancel);
        emitter.onError(error -> cancel.run());
        emitter.onTimeout(cancel);
        try {
            executor.execute(() -> {
                try {
                    run.run();
                } finally {
                    heartbeat.cancel(true);
                }
            });
        } catch (RejectedExecutionException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DSH stream executor is unavailable", e);
        }
        if (!heartbeatInterval.isZero()) {
            Thread.ofVirtual().name("dsh-sse-heartbeat").start(heartbeat);
        }
        return emitter;
    }

    /**
     * 在 DSH 静默时发送 SSE 注释，通过写入失败或容器回调发现断连。
     *
     * @param emitter 当前 HTTP 请求的发射器
     * @param run 在 JVM 内等待 DSH 的任务
     */
    private void sendHeartbeats(DshSseEmitter emitter, FutureTask<Void> run) {
        try {
            while (!run.isDone()) {
                Thread.sleep(heartbeatInterval);
                emitter.heartbeat();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException e) {
            LOG.log(DEBUG, "SSE heartbeat could not be delivered; ending the Java wait");
            run.cancel(true);
        }
    }

    /**
     * 桥接 SDK 通知并在执行结算后结束 SSE。
     *
     * @param command 用户输入
     * @param emitter 当前 HTTP 请求的 SSE 发射器
     */
    private void stream(DshRunCmd command, DshSseEmitter emitter) {
        try {
            DshRunResponse result = service.run(command,
                    event -> emitter.sendEvent(event.method(), event.paramsJson()));
            emitter.finish("result", result);
        } catch (DshRunException e) {
            try {
                emitter.finish("error", Map.of("code", e.kind().name(), "message", e.getMessage()));
            } catch (UncheckedIOException ignored) {
                // Servlet 容器负责客户端断开后的连接清理。
            }
        } catch (UncheckedIOException ignored) {
            // 发送失败会退出本次事件消费，由容器清理连接。
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (RuntimeException e) {
            emitter.completeWithError(e);
        }
    }

    /**
     * 按同步接口的会话、启动或执行错误选择 HTTP 状态。
     *
     * @param error 可公开的执行异常
     * @return HTTP 问题详情
     */
    @ExceptionHandler(DshRunException.class)
    public ProblemDetail handleRunFailure(DshRunException error) {
        HttpStatus status = switch (error.kind()) {
            case TIMEOUT, STARTUP_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case INVALID_SESSION_ID -> HttpStatus.BAD_REQUEST;
            case SESSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SESSION_BUSY -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, error.getMessage());
        detail.setProperty("code", error.kind().name());
        return detail;
    }
}
