package cn.zntime.dsh.bridge.domain.gateway;

import cn.zntime.dsh.bridge.client.dto.co.DshRunEvent;
import cn.zntime.dsh.bridge.client.dto.co.DshRunResponse;

import java.util.function.Consumer;

/** 供应用层执行 DSH 会话的端口。 */
public interface DshGateway {
    /**
     * 在新建或指定的会话中提交输入并等待结束。
     *
     * @param text 用户输入
     * @param sessionId 前一轮返回的会话 ID，为 {@code null} 时创建新会话
     * @param observer 会话事件消费者
     * @return 本次运行结果
     * @throws InterruptedException 等待运行时被中断
     */
    DshRunResponse run(String text, String sessionId, Consumer<DshRunEvent> observer) throws InterruptedException;
}
