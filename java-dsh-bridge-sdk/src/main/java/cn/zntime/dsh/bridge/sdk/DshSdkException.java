package cn.zntime.dsh.bridge.sdk;

/** 标识 SDK 握手、传输、协议或远端调用失败。 */
public final class DshSdkException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** 宿主可用来区分超时和上游失败的错误类别。 */
    public enum Kind {
        /** 等待握手、RPC 或事件超出配置期限。 */
        TIMEOUT,
        /** 操作系统未能启动 SDK 子进程。 */
        LAUNCH,
        /** 子进程的标准输入输出连接已断开。 */
        TRANSPORT,
        /** DSH 返回的结构不符合当前 SDK 协议。 */
        PROTOCOL,
        /** DSH 明确返回了 JSON-RPC 错误。 */
        REMOTE,
        /** 会话 ID 与本 SDK 生成的格式不匹配。 */
        INVALID_SESSION_ID,
        /** 指定会话在当前进程或持久化存储中不存在。 */
        SESSION_NOT_FOUND,
        /** 当前 SDK 正在执行同一会话的另一轮。 */
        SESSION_BUSY,
        /** 当次运行的事件消费者耗尽独立缓冲。 */
        SLOW_CONSUMER
    }

    /** 对调用方保持稳定的失败类别。 */
    private final Kind kind;
    /** 失败是否发生在子进程启动或握手阶段。 */
    private final boolean startupFailure;

    /**
     * 创建无底层异常的 SDK 错误。
     *
     * @param kind 错误类别
     * @param message 诊断信息
     */
    DshSdkException(Kind kind, String message) {
        this(kind, message, null);
    }

    /**
     * 创建 SDK 错误并保留底层原因。
     *
     * @param kind 错误类别
     * @param message 诊断信息
     * @param cause 底层原因
     */
    DshSdkException(Kind kind, String message, Throwable cause) {
        this(kind, message, cause, kind == Kind.LAUNCH);
    }

    /**
     * 创建带启动阶段标记的 SDK 错误。
     *
     * @param kind 错误类别
     * @param message 诊断信息
     * @param cause 底层原因
     * @param startupFailure 失败是否发生在启动阶段
     */
    private DshSdkException(Kind kind, String message, Throwable cause, boolean startupFailure) {
        super(message, cause);
        this.kind = kind;
        this.startupFailure = startupFailure;
    }

    /**
     * 将子进程握手失败标为启动错误，并保留原异常和错误类别。
     *
     * @return 带有启动阶段标记的握手异常
     */
    DshSdkException duringInitialization() {
        return new DshSdkException(kind, getMessage(), this, true);
    }

    /**
     * 返回错误类别。
     *
     * @return 错误类别
     */
    public Kind kind() {
        return kind;
    }

    /**
     * 判断错误来自启动进程或等待 initialize 握手的阶段。
     *
     * @return 来自启动阶段时为 true
     */
    public boolean isStartupFailure() {
        return startupFailure;
    }
}
