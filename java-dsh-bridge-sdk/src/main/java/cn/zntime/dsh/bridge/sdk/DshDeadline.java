package cn.zntime.dsh.bridge.sdk;

import java.time.Duration;

import static cn.zntime.dsh.bridge.sdk.DshSdkException.Kind.TIMEOUT;

/** 以单调时钟计算等待预算，供同一次执行的 RPC 和事件消费共用。 */
final class DshDeadline {
    private final long started = System.nanoTime();
    private final long budget;

    /**
     * 从当前时间开始计时。
     *
     * @param timeout 等待期限，零表示无期限
     */
    DshDeadline(Duration timeout) {
        budget = timeout.toNanos();
    }

    /**
     * 计算下一次阻塞调用的剩余期限。
     *
     * @return 剩余纳秒，无期限时为最大值
     */
    long remainingNanos() {
        if (budget == 0) {
            return Long.MAX_VALUE;
        }
        long nanos = budget - (System.nanoTime() - started);
        if (nanos <= 0) {
            throw new DshSdkException(TIMEOUT, "DSH execution exceeded its deadline");
        }
        return nanos;
    }
}
