package cn.zntime.dsh.bridge.sdk;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static cn.zntime.dsh.bridge.sdk.DshSdkException.Kind.INVALID_SESSION_ID;
import static cn.zntime.dsh.bridge.sdk.DshSdkException.Kind.PROTOCOL;
import static cn.zntime.dsh.bridge.sdk.DshSdkException.Kind.SESSION_BUSY;
import static cn.zntime.dsh.bridge.sdk.DshSdkException.Kind.SESSION_NOT_FOUND;

/** 供 Java 应用使用的 DSH 子进程客户端。多个会话可共享一个 SDK 实例。 */
public final class DshSdk implements AutoCloseable {
    private static final Pattern SESSION_ID = Pattern.compile("session-[0-9a-f]{32}");

    private final DshSdkConfig config;
    private final Object lifecycle = new Object();
    /** 只跟踪在途请求，避免同一会话的通知被两轮同时收集。 */
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();
    private DshConnection connection;
    private boolean closed;

    /**
     * 创建惰性启动的 SDK。
     *
     * @param config 进程与会话配置
     */
    public DshSdk(DshSdkConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    /**
     * 启动进程并等待 initialize 握手，可在接收请求前主动调用。
     *
     * @throws InterruptedException 等待握手时被中断
     */
    public void start() throws InterruptedException {
        openConnection();
    }

    /**
     * 在独立会话中运行输入并等待最终结果。
     *
     * @param text 用户输入
     * @return 本次会话结果
     * @throws InterruptedException 等待时被中断
     */
    public DshRunResult run(String text) throws InterruptedException {
        return runStreaming(text, ignored -> { });
    }

    /**
     * 在新建或已有会话中运行输入并等待最终结果。
     *
     * @param text 用户输入
     * @param sessionId 前一轮返回的会话 ID，为 {@code null} 时创建新会话
     * @return 本次会话结果
     * @throws InterruptedException 等待时被中断
     */
    public DshRunResult run(String text, String sessionId) throws InterruptedException {
        return runStreaming(text, sessionId, ignored -> { });
    }

    /**
     * 在独立会话中运行输入，并在当前调用线程依序投递属于本次运行的事件。
     *
     * @param text 用户输入
     * @param observer 事件消费者
     * @return 本次会话结果
     * @throws InterruptedException 等待应答或事件时被中断
     */
    public DshRunResult runStreaming(String text, Consumer<DshEvent> observer) throws InterruptedException {
        return runStreaming(text, null, observer);
    }

    /**
     * 在新建或已有会话中运行输入，按顺序投递本轮事件。每个会话同时只能运行一轮。
     *
     * @param text 用户输入
     * @param requestedSessionId 前一轮返回的会话 ID，为 {@code null} 时创建新会话
     * @param observer 事件消费者
     * @return 本轮执行结果
     * @throws InterruptedException 等待应答或事件时被中断
     */
    public DshRunResult runStreaming(String text, String requestedSessionId, Consumer<DshEvent> observer)
            throws InterruptedException {
        if (Objects.requireNonNull(text).isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        Objects.requireNonNull(observer);
        if (requestedSessionId != null && !SESSION_ID.matcher(requestedSessionId).matches()) {
            throw new DshSdkException(INVALID_SESSION_ID, "Invalid DSH session ID");
        }
        String sessionId = requestedSessionId == null
                ? "session-" + UUID.randomUUID().toString().replace("-", "") : requestedSessionId;
        if (!activeSessions.add(sessionId)) {
            throw new DshSdkException(SESSION_BUSY, "Another run is already active for this DSH session");
        }
        try {
            DshConnection active = openConnection();
            DshDeadline deadline = new DshDeadline(config.runTimeout());
            if (requestedSessionId != null) {
                awaitSessionReady(active, sessionId, deadline);
            }
            EventSubscription subscription = active.subscribe(sessionId);
            try {
                ObjectNode params = DshConnection.object().put("sessionId", sessionId);
                params.putArray("contentBlocks").addObject().put("type", "text").put("text", text);
                JsonNode receipt = active.request("session/query", params, deadline);
                return collect(sessionId, receipt, subscription, deadline, observer);
            } finally {
                active.unsubscribe(sessionId);
            }
        } finally {
            activeSessions.remove(sessionId);
        }
    }

    /**
     * 等待上轮 DSH 活动完成，进程内找不到会话时从配置的 DSH 数据目录恢复。
     *
     * @param active 已初始化的 DSH 连接
     * @param sessionId 已有会话 ID
     * @param deadline 本次执行的截止时间
     * @throws InterruptedException 等待会话可用时被中断
     */
    private static void awaitSessionReady(DshConnection active, String sessionId, DshDeadline deadline)
            throws InterruptedException {
        ObjectNode params = DshConnection.object().put("sessionId", sessionId);
        try {
            // JVM 等待超时或 SSE 断开后，DSH 仍可能处理上一轮。
            active.request("session/wait", params, deadline);
        } catch (DshSdkException e) {
            if (e.kind() != SESSION_NOT_FOUND) {
                throw e;
            }
            active.request("session/resume", params, deadline);
            active.request("session/wait", params, deadline);
        }
    }

    /**
     * 获取一个已完成握手的连接，原连接失效时为后续调用重建进程。
     *
     * @return 可用于会话请求的连接
     * @throws InterruptedException 等待握手时被中断
     */
    private DshConnection openConnection() throws InterruptedException {
        DshConnection active;
        synchronized (lifecycle) {
            if (closed) {
                throw new IllegalStateException("DSH SDK is closed");
            }
            if (connection == null || !connection.isUsable()) {
                if (connection != null) {
                    connection.close();
                }
                connection = new DshConnection(config);
            }
            active = connection;
        }
        active.awaitInitialized();
        return active;
    }

    /**
     * 按回执关联事件并等到对应的结束信号。
     *
     * @param sessionId 本次根会话 ID
     * @param receipt session/query 回执
     * @param subscription 会话树订阅
     * @param deadline 本次执行的截止时间
     * @param observer 事件消费者
     * @return 普通输入的最终回复或命令完成结果
     * @throws InterruptedException 等待事件时被中断
     */
    private static DshRunResult collect(String sessionId, JsonNode receipt, EventSubscription subscription,
                                        DshDeadline deadline, Consumer<DshEvent> observer) throws InterruptedException {
        String kind = receipt.path("kind").stringValue("");
        String activityId = switch (kind) {
            case "prompt" -> requiredText(receipt, "messageId");
            case "command" -> requiredText(receipt.path("execution"), "commandId");
            default -> throw new DshSdkException(PROTOCOL, "Unexpected session/query result kind");
        };
        boolean command = "command".equals(kind);
        boolean started = false;
        boolean commandDone = false;
        DshRunResult commandResult = null;
        String text = "";
        String finishReason = null;

        while (true) {
            EventSubscription.Notification notification = subscription.take(deadline);
            JsonNode params = notification.params();
            String method = notification.method();
            boolean root = sessionId.equals(params.path("sessionId").stringValue(""));
            if ("session.query.completed".equals(method)) {
                if (command && root && activityId.equals(params.path("commandId").stringValue(""))) {
                    // 部分命令会先发完成通知，再补发 command/done。
                    commandResult = new DshRunResult(sessionId, requiredText(params, "finalResponse", true),
                            requiredText(params, "finishReason"));
                    if (commandDone) {
                        return commandResult;
                    }
                }
                continue;
            }

            JsonNode event = root && "session.event".equals(method) ? params.path("event") : null;
            if (event != null) {
                JsonNode data = event.path("data");
                String type = event.path("type").stringValue("");
                if (!started) {
                    started = command
                            ? "command/run".equals(type) && activityId.equals(data.path("commandId").stringValue(""))
                            : "agent/inbox/spliced".equals(type) && matchesMessage(data.path("inserted"), activityId);
                }
                if (started) {
                    switch (type) {
                        case "assistant/message" -> text = assistantText(data.path("message").path("content"));
                        case "turn/end" -> finishReason = requiredText(data.path("reason"), "kind");
                        case "command/done" -> commandDone |= command
                                && activityId.equals(data.path("commandId").stringValue(""));
                        default -> { }
                    }
                }
            }
            if (!started) {
                continue;
            }

            observer.accept(new DshEvent(method, params.toString()));
            if (commandDone && commandResult != null) {
                return commandResult;
            }
            if (!command && root && "session.status".equals(method)
                    && "idle".equals(params.path("status").stringValue(""))) {
                return new DshRunResult(sessionId, text, finishReason);
            }
        }
    }

    /**
     * 检查消息是否出现在 DSH 的入队事件中。
     *
     * @param inserted 入队消息数组
     * @param id 回执中的消息 ID
     * @return 是否匹配当前输入
     */
    private static boolean matchesMessage(JsonNode inserted, String id) {
        if (inserted.isArray()) {
            for (JsonNode message : inserted) {
                if (id.equals(message.path("id").stringValue(""))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 按原顺序提取一条助手消息的文本块。
     *
     * @param content 助手消息的内容块
     * @return 拼接后的文本
     */
    private static String assistantText(JsonNode content) {
        if (!content.isArray()) {
            throw new DshSdkException(PROTOCOL, "Invalid assistant content");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").stringValue(""))) {
                text.append(requiredText(block, "text", true));
            }
        }
        return text.toString();
    }

    /**
     * 读取协议中的必填标识字段。
     *
     * @param value 字段所在的 JSON 对象
     * @param key 字段名称
     * @return 非空字符串
     */
    private static String requiredText(JsonNode value, String key) {
        return requiredText(value, key, false);
    }

    /**
     * 读取必填文本字段，按协议决定是否允许空文本。
     *
     * @param value 字段所在的 JSON 对象
     * @param key 字段名称
     * @param allowEmpty 是否允许空字符串
     * @return 字符串值
     */
    private static String requiredText(JsonNode value, String key, boolean allowEmpty) {
        String text = value.path(key).stringValue(null);
        if (text == null || (!allowEmpty && text.isBlank())) {
            throw new DshSdkException(PROTOCOL, "Invalid DSH protocol field: " + key);
        }
        return text;
    }

    /** 关闭当前 SDK 并停止接受新会话。 */
    @Override
    public void close() {
        DshConnection active;
        synchronized (lifecycle) {
            closed = true;
            active = connection;
            connection = null;
        }
        if (active != null) {
            active.close();
        }
    }
}
