package cn.zntime.dsh.bridge.client.dto.co;

/**
 * 从本次会话收到的 DSH 通知。
 *
 * @param method 通知方法
 * @param paramsJson JSON 对象格式的通知参数
 */
public record DshRunEvent(String method, String paramsJson) {
}
