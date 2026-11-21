package cn.zntime.dsh.bridge.client.api;

/** 应用层可向调用方报告的 DSH 执行失败。 */
public final class DshRunException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** DSH 执行失败类别。 */
    public enum Kind {
        /** SDK 在执行阶段的等待超出配置期限。 */
        TIMEOUT,
        /** SDK 初始化握手超出配置期限。 */
        STARTUP_TIMEOUT,
        /** SDK 子进程启动或初始化失败。 */
        STARTUP_ERROR,
        /** 传入的会话 ID 格式无效。 */
        INVALID_SESSION_ID,
        /** 指定会话在 DSH 中不存在。 */
        SESSION_NOT_FOUND,
        /** 同一会话已有请求在处理。 */
        SESSION_BUSY,
        /** DSH 进程、协议或调用失败。 */
        UPSTREAM_ERROR
    }

    /** 应用接口的失败分类。 */
    private final Kind kind;

    /**
     * 创建可供应用接口返回的执行错误。
     *
     * @param kind 错误类别
     * @param message 可公开的错误信息
     * @param cause 底层异常
     */
    public DshRunException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /**
     * 返回可供接口映射的失败类别。
     *
     * @return 失败类别
     */
    public Kind kind() {
        return kind;
    }
}
