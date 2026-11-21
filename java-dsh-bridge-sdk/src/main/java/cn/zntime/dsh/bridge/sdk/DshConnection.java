package cn.zntime.dsh.bridge.sdk;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static cn.zntime.dsh.bridge.sdk.DshSdkException.Kind.LAUNCH;
import static cn.zntime.dsh.bridge.sdk.DshSdkException.Kind.PROTOCOL;
import static cn.zntime.dsh.bridge.sdk.DshSdkException.Kind.REMOTE;
import static cn.zntime.dsh.bridge.sdk.DshSdkException.Kind.SESSION_NOT_FOUND;
import static cn.zntime.dsh.bridge.sdk.DshSdkException.Kind.TIMEOUT;
import static cn.zntime.dsh.bridge.sdk.DshSdkException.Kind.TRANSPORT;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static java.nio.charset.StandardCharsets.UTF_8;

/** 在一个 DSH 进程上复用 JSONL 请求、响应与会话通知。 */
final class DshConnection implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(DshConnection.class.getName());
    private static final String JSON_RPC = "2.0";
    private static final int STDERR_LIMIT = 16_384;
    private static final JsonMapper JSON = JsonMapper.shared();

    private final Process process;
    private final BufferedWriter stdin;
    private final ExecutorService writer;
    private final AtomicLong requestIds = new AtomicLong();
    private final Object state = new Object();
    private final Map<String, CompletableFuture<JsonNode>> pending = new HashMap<>();
    private final Map<String, EventSubscription> subscriptions = new HashMap<>();
    private final CompletableFuture<Void> initialized = new CompletableFuture<>();
    private final StringBuilder stderr = new StringBuilder();
    private volatile DshSdkException failure;
    private volatile boolean closing;
    private boolean closed;

    /**
     * 根据配置启动 DSH，并在后台执行握手。
     *
     * @param config 进程及会话配置
     */
    DshConnection(DshSdkConfig config) {
        List<String> command = new ArrayList<>(config.command());
        command.add("--profile");
        command.add(config.profile());
        config.patchFiles().forEach(path -> {
            command.add("--patch");
            command.add(path.toString());
        });

        ProcessBuilder builder = new ProcessBuilder(command).directory(config.workingDirectory().toFile());
        if (config.dshHome() != null) {
            builder.environment().put("DSH_HOME", config.dshHome().toString());
        }
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new DshSdkException(LAUNCH, "Could not start the DSH SDK process", e);
        }

        long pid = process.pid();
        stdin = process.outputWriter(UTF_8);
        writer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("dsh-sdk-write-" + pid).factory());
        Thread.ofPlatform().daemon().name("dsh-sdk-stderr-" + pid).start(this::readDiagnostics);
        Thread.ofPlatform().daemon().name("dsh-sdk-read-" + pid).start(this::readFrames);
        Thread.ofVirtual().name("dsh-sdk-initialize-" + pid).start(() -> initialize(config));
        LOG.log(INFO, "Started DSH SDK process pid={0}, profile={1}", pid, config.profile());
    }

    /**
     * 完成会话配置握手，并使所有使用本进程的调用方看到相同的结果。
     *
     * @param config 握手配置
     */
    private void initialize(DshSdkConfig config) {
        try {
            JsonNode info = request("initialize", object()
                    .put("cwd", config.workingDirectory().toString())
                    .put("provider", config.provider())
                    .put("model", config.model()), new DshDeadline(config.initializeTimeout()))
                    .path("serverInfo");
            if (!"deepseek-harness-sdk-runtime".equals(info.path("name").stringValue(""))
                    || info.path("version").stringValue("").isBlank()) {
                throw new DshSdkException(PROTOCOL, "Unexpected DSH SDK server identity");
            }
            initialized.complete(null);
        } catch (InterruptedException e) {
            fail(transport("DSH initialization was interrupted", e));
            Thread.currentThread().interrupt();
        } catch (DshSdkException e) {
            fail(e);
        } finally {
            if (initialized.isCompletedExceptionally()) {
                close();
            }
        }
    }

    /**
     * 等待已经启动的进程完成握手。
     *
     * @throws InterruptedException 调用方中断时抛出
     */
    void awaitInitialized() throws InterruptedException {
        try {
            initialized.get();
        } catch (ExecutionException e) {
            throw ((DshSdkException) e.getCause()).duringInitialization();
        }
    }

    /**
     * 判断是否还能在本连接上发起新请求。
     *
     * @return 连接可用时为 true
     */
    boolean isUsable() {
        return failure == null && !closing && process.isAlive();
    }

    /**
     * 分配供调用方构建协议载荷的 JSON 对象。
     *
     * @return 空 JSON 对象
     */
    static ObjectNode object() {
        return JSON.createObjectNode();
    }

    /**
     * 发出一个请求并在执行期限内等待对应的 JSON-RPC 应答。
     *
     * @param method RPC 方法
     * @param params 请求参数
     * @param deadline 共享的等待期限
     * @return 应答中的 result 对象
     * @throws InterruptedException 调用方中断时抛出
     */
    JsonNode request(String method, ObjectNode params, DshDeadline deadline) throws InterruptedException {
        deadline.remainingNanos();
        String id = Long.toString(requestIds.incrementAndGet());
        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        synchronized (state) {
            checkConnection("shutdown".equals(method));
            pending.put(id, answer);
        }
        try {
            ObjectNode frame = object().put("jsonrpc", JSON_RPC).put("id", id).put("method", method);
            frame.set("params", params);
            send(frame, answer);
            JsonNode response = answer.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            JsonNode error = response.path("error");
            if (error.isObject()) {
                String message = error.path("message").stringValue("DSH JSON-RPC error");
                DshSdkException.Kind kind = isMissingSession(method, params.path("sessionId").stringValue(""), message)
                        ? SESSION_NOT_FOUND : REMOTE;
                throw new DshSdkException(kind, method + " failed (" + error.path("code").asString("unknown")
                        + "): " + message);
            }
            JsonNode result = response.path("result");
            if (!result.isObject()) {
                throw new DshSdkException(PROTOCOL, method + " returned an invalid result");
            }
            return result;
        } catch (ExecutionException e) {
            throw (DshSdkException) e.getCause();
        } catch (TimeoutException e) {
            throw new DshSdkException(TIMEOUT, "Timed out waiting for " + method, e);
        } finally {
            answer.cancel(false);
            synchronized (state) {
                pending.remove(id);
            }
        }
    }

    /**
     * 识别 DSH SDK 将会话查找失败序列化成的固定错误消息。
     *
     * @param method RPC 方法名
     * @param sessionId 请求中的会话 ID
     * @param message DSH 返回的错误描述
     * @return 错误是否表示当前进程或持久化存储中没有对应会话
     */
    private static boolean isMissingSession(String method, String sessionId, String message) {
        return switch (method) {
            case "session/wait" -> message.equals("unknown SDK session: " + sessionId);
            case "session/resume" -> message.equals("session \"" + sessionId + "\" not found");
            default -> false;
        };
    }

    /**
     * 检查连接状态，允许关闭流程发送最后一个 shutdown 请求。
     *
     * @param allowClosing 是否属于关闭流程
     */
    private void checkConnection(boolean allowClosing) {
        if (failure != null) {
            throw failure;
        }
        if (closing && !allowClosing) {
            throw transport("DSH connection is closing", null);
        }
    }

    /**
     * 在发出会话请求前登记订阅，以接住早于 RPC 应答的事件。
     *
     * @param sessionId 根会话 ID
     * @return 已经注册的订阅
     */
    EventSubscription subscribe(String sessionId) {
        synchronized (state) {
            checkConnection(false);
            EventSubscription subscription = new EventSubscription(sessionId);
            subscriptions.put(sessionId, subscription);
            return subscription;
        }
    }

    /**
     * 移除当前运行的通知订阅。
     *
     * @param sessionId 根会话 ID
     */
    void unsubscribe(String sessionId) {
        synchronized (state) {
            subscriptions.remove(sessionId);
        }
    }

    /**
     * 排队写入完整的一行 JSON，防止并发请求写入交错。
     *
     * @param frame 请求或宿主应答
     * @param answer 关联的等待项，宿主应答时为空
     */
    private void send(ObjectNode frame, CompletableFuture<JsonNode> answer) {
        try {
            writer.execute(() -> {
                if (answer != null && answer.isDone()) {
                    return;
                }
                try {
                    stdin.write(frame.toString());
                    stdin.newLine();
                    stdin.flush();
                } catch (IOException e) {
                    fail(transport("Cannot write to DSH stdin", e));
                }
            });
        } catch (RejectedExecutionException e) {
            fail(transport("DSH writer is closed", e));
        }
    }

    /** 按行读取 DSH stdout 并分发有效的 JSON-RPC 消息。 */
    private void readFrames() {
        try (BufferedReader stdout = process.inputReader(UTF_8)) {
            String line;
            while ((line = stdout.readLine()) != null) {
                try {
                    dispatch(JSON.readTree(line));
                } catch (JacksonException e) {
                    LOG.log(DEBUG, "Ignored a non-JSON line on DSH stdout, pid={0}", process.pid());
                }
            }
            fail(transport("DSH stdout closed", null));
        } catch (IOException | RuntimeException e) {
            fail(transport("Cannot read DSH stdout", e));
        }
    }

    /**
     * 识别 RPC 应答、宿主请求和异步通知。
     *
     * @param frame 解码后的 JSON 帧
     */
    private void dispatch(JsonNode frame) {
        if (frame == null || !JSON_RPC.equals(frame.path("jsonrpc").stringValue(""))) {
            return;
        }
        JsonNode id = frame.path("id");
        String method = frame.path("method").stringValue(null);
        if (id.isString() || id.isIntegralNumber()) {
            if (method != null) {
                ObjectNode response = object().put("jsonrpc", JSON_RPC);
                response.set("id", id);
                response.putObject("error").put("code", -32601).put("message", "Host method not configured");
                send(response, null);
                return;
            }
            CompletableFuture<JsonNode> answer;
            synchronized (state) {
                answer = pending.remove(id.asString());
            }
            if (answer != null) {
                answer.complete(frame);
            }
        } else if (method != null && frame.path("params").isObject()) {
            EventSubscription.Notification notification = new EventSubscription.Notification(method, frame.path("params"));
            List<EventSubscription> active;
            synchronized (state) {
                active = List.copyOf(subscriptions.values());
            }
            active.forEach(subscription -> subscription.offer(notification));
        }
    }

    /** 独立排空 stderr 并保留有限的诊断尾部。 */
    private void readDiagnostics() {
        try (var reader = process.errorReader(UTF_8)) {
            char[] buffer = new char[2048];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                synchronized (stderr) {
                    stderr.append(buffer, 0, count);
                    if (stderr.length() > STDERR_LIMIT) {
                        stderr.delete(0, stderr.length() - STDERR_LIMIT);
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(DEBUG, "DSH stderr closed, pid={0}", process.pid());
        }
    }

    /**
     * 创建携带进程定位信息的传输异常。
     *
     * @param message 失败阶段
     * @param cause 可选的底层异常
     * @return 用于 SDK 调用方诊断的异常
     */
    private DshSdkException transport(String message, Throwable cause) {
        synchronized (stderr) {
            return new DshSdkException(TRANSPORT, message + " (pid " + process.pid() + ")\n" + stderr, cause);
        }
    }

    /**
     * 报告连接终态并唤醒正在等待握手、应答或事件的调用方。
     *
     * @param error 首个终止原因
     */
    private void fail(DshSdkException error) {
        synchronized (state) {
            if (failure != null) {
                return;
            }
            failure = error;
            initialized.completeExceptionally(error);
            pending.values().forEach(answer -> answer.completeExceptionally(error));
            pending.clear();
            subscriptions.values().forEach(subscription -> subscription.fail(error));
            subscriptions.clear();
        }
    }

    /** 尝试协议关闭，并在有界等待后回收仍在运行的进程及已观察到的后代。 */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closing = true;
        ProcessHandle handle = process.toHandle();
        List<ProcessHandle> descendants = new ArrayList<>(descendants(handle));
        boolean interrupted = Thread.interrupted();
        try {
            if (!interrupted && handle.isAlive() && failure == null
                    && initialized.isDone() && !initialized.isCompletedExceptionally()) {
                try {
                    request("shutdown", object(), new DshDeadline(Duration.ofSeconds(1)));
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (DshSdkException e) {
                    LOG.log(DEBUG, "DSH protocol shutdown did not finish, pid={0}", process.pid());
                }
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            fail(transport("DSH connection closed", null));
            writer.shutdownNow();
            descendants.addAll(descendants(handle));
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
            try {
                if (!interrupted && !process.waitFor(2, TimeUnit.SECONDS)) {
                    LOG.log(WARNING, "Could not confirm DSH process exit, pid={0}", process.pid());
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
            closed = true;
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            LOG.log(INFO, "Closed DSH SDK connection pid={0}", process.pid());
        }
    }

    /**
     * 获取可见的后代进程，宿主限制进程枚举时仍允许回收直属 DSH。
     *
     * @param handle DSH 进程句柄
     * @return 当前可枚举的后代
     */
    private static List<ProcessHandle> descendants(ProcessHandle handle) {
        try (var children = handle.descendants()) {
            return children.toList();
        } catch (RuntimeException e) {
            LOG.log(DEBUG, "Could not enumerate DSH subprocesses, pid={0}", handle.pid());
            return List.of();
        }
    }
}
