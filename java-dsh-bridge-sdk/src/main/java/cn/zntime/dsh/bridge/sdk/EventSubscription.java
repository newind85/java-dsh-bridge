package cn.zntime.dsh.bridge.sdk;

import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static cn.zntime.dsh.bridge.sdk.DshSdkException.Kind.SLOW_CONSUMER;

/** 为一次运行缓冲根会话和已发现的子会话通知。 */
final class EventSubscription {
    private static final int CAPACITY = 8192;

    /** 进程读线程分发的原始通知。 */
    record Notification(String method, JsonNode params) {
    }

    private final Set<String> sessions = new HashSet<>();
    private final Queue<Notification> queue = new ArrayDeque<>();
    private DshSdkException failure;

    /**
     * 为根会话创建一个独立订阅。
     *
     * @param sessionId 根会话 ID
     */
    EventSubscription(String sessionId) {
        sessions.add(sessionId);
    }

    /**
     * 接收属于当前会话树的通知，缓冲已满时中止本次订阅。
     *
     * @param notification DSH 通知
     */
    synchronized void offer(Notification notification) {
        if (failure != null || !belongs(notification)) {
            return;
        }
        if (queue.size() == CAPACITY) {
            queue.clear();
            fail(new DshSdkException(SLOW_CONSUMER, "DSH event subscriber could not keep up"));
            return;
        }
        queue.add(notification);
        notifyAll();
    }

    /**
     * 判断通知是否属于已登记的会话，遇到子会话创建时记录其 ID。
     *
     * @param notification DSH 通知
     * @return 是否属于当前会话树
     */
    private boolean belongs(Notification notification) {
        JsonNode params = notification.params();
        if ("subagent.started".equals(notification.method())) {
            String childId = params.path("childSessionId").asString("");
            if (sessions.contains(params.path("parentSessionId").asString("")) && !childId.isBlank()) {
                sessions.add(childId);
                return true;
            }
            return false;
        }
        if ("subagent.finished".equals(notification.method())) {
            return sessions.contains(params.path("parentSessionId").asString(""))
                    || sessions.contains(params.path("childSessionId").asString(""));
        }
        return sessions.contains(params.path("sessionId").asString(""));
    }

    /**
     * 等待下一条通知，已接收的事件按原有顺序消费。
     *
     * @param deadline 本次运行的截止时间
     * @return 下一条通知
     * @throws InterruptedException 宿主中断等待时抛出
     */
    synchronized Notification take(DshDeadline deadline) throws InterruptedException {
        while (queue.isEmpty() && failure == null) {
            TimeUnit.NANOSECONDS.timedWait(this, deadline.remainingNanos());
        }
        if (!queue.isEmpty()) {
            deadline.remainingNanos();
            return queue.remove();
        }
        throw failure;
    }

    /**
     * 记录连接错误并唤醒等待者。
     *
     * @param error 订阅终止原因
     */
    synchronized void fail(DshSdkException error) {
        if (failure == null) {
            failure = error;
            notifyAll();
        }
    }
}
