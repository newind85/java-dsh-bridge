package cn.zntime.dsh.bridge.client.api;

import cn.zntime.dsh.bridge.client.dto.cmd.DshRunCmd;
import cn.zntime.dsh.bridge.client.dto.co.DshRunEvent;
import cn.zntime.dsh.bridge.client.dto.co.DshRunResponse;

import java.util.function.Consumer;

/** 提供单次 DSH 会话执行的应用服务。 */
public interface DshRunServiceI {
    /**
     * 执行一次输入并返回最终结果。
     *
     * @param command 运行输入
     * @return 执行结果
     * @throws InterruptedException 等待执行时被中断
     */
    default DshRunResponse run(DshRunCmd command) throws InterruptedException {
        return run(command, ignored -> { });
    }

    /**
     * 执行一次输入，在等待最终结果时逐条交付运行事件。
     *
     * @param command 运行输入
     * @param observer 事件消费者
     * @return 执行结果
     * @throws InterruptedException 等待执行时被中断
     */
    DshRunResponse run(DshRunCmd command, Consumer<DshRunEvent> observer) throws InterruptedException;
}
