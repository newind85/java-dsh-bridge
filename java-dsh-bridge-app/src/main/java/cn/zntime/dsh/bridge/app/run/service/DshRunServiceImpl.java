package cn.zntime.dsh.bridge.app.run.service;

import cn.zntime.dsh.bridge.client.api.DshRunServiceI;
import cn.zntime.dsh.bridge.client.dto.cmd.DshRunCmd;
import cn.zntime.dsh.bridge.client.dto.co.DshRunEvent;
import cn.zntime.dsh.bridge.client.dto.co.DshRunResponse;
import cn.zntime.dsh.bridge.domain.gateway.DshGateway;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/** 执行应用层的 DSH 运行用例。 */
@Service
public class DshRunServiceImpl implements DshRunServiceI {
    private final DshGateway gateway;

    /**
     * 注入 DSH 运行端口。
     *
     * @param gateway 运行端口
     */
    public DshRunServiceImpl(DshGateway gateway) {
        this.gateway = gateway;
    }

    /** {@inheritDoc} */
    @Override
    public DshRunResponse run(DshRunCmd command, Consumer<DshRunEvent> observer) throws InterruptedException {
        return gateway.run(command.text(), command.sessionId(), observer);
    }
}
