package cn.zntime.dsh.bridge.client.dto.co;

/**
 * 一次 DSH 运行的最终响应。
 *
 * @param sessionId 本次执行的 DSH 会话 ID
 * @param text 最终回复或命令输出
 * @param finishReason DSH 结束原因，未收到结束事件时为空
 */
public record DshRunResponse(String sessionId, String text, String finishReason) {
}
