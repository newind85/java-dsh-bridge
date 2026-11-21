package cn.zntime.dsh.bridge.client.dto.cmd;

import jakarta.validation.constraints.NotBlank;

/**
 * 在新建或已有的 DSH 会话中提交一次运行。
 *
 * @param text 要交给 DSH 的文本
 * @param sessionId 前一轮返回的会话 ID，省略时创建新会话
 */
public record DshRunCmd(@NotBlank String text, String sessionId) {
    /**
     * 创建在新会话中执行的命令。
     *
     * @param text 用户输入
     */
    public DshRunCmd(String text) {
        this(text, null);
    }
}