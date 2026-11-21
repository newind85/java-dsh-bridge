package cn.zntime.dsh.bridge.adapter.web;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;

/** 协调单个 SSE 连接中的事件、心跳和最终结果。 */
final class DshSseEmitter extends SseEmitter {
    private boolean finished;

    /** 创建由 Bridge 运行期限和连接生命周期控制的流。 */
    DshSseEmitter() {
        super(0L);
    }

    /**
     * 在连接空闲时写入 SSE 注释，与终态事件共用发送锁。
     *
     * @throws IOException 客户端连接写入失败
     */
    void heartbeat() throws IOException {
        // 正在发送业务事件时，同一次写入已承担检测断连的职责。
        if (!writeLock.tryLock()) {
            return;
        }
        try {
            if (!finished) {
                send(event().comment("heartbeat"));
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 发送一个 JSON 数据事件。
     *
     * @param name SSE 事件名称
     * @param data JSON 字符串或待序列化的数据
     */
    void sendEvent(String name, Object data) {
        try {
            send(event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 发送终态事件并结束流，心跳在终态后停止写入。
     *
     * @param name result 或 error 事件名称
     * @param data 最终结果或错误详情
     */
    void finish(String name, Object data) {
        writeLock.lock();
        try {
            finished = true;
            sendEvent(name, data);
            complete();
        } finally {
            writeLock.unlock();
        }
    }
}
