package cn.zntime.dsh.bridge.sdk;

/**
 * 由 DSH 会话活动汇总得到的一次执行结果。
 *
 * @param sessionId DSH 会话 ID
 * @param text 最终回复或命令输出
 * @param finishReason DSH 提供的结束原因，未产生结束事件时为空
 */
public record DshRunResult(String sessionId, String text, String finishReason) {
}
