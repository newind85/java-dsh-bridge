package cn.zntime.dsh.bridge.sdk;

/**
 * 转发给宿主的 DSH 通知，保留 wire method 以及原始参数。
 *
 * @param method JSON-RPC 通知方法
 * @param paramsJson JSON 对象格式的参数
 */
public record DshEvent(String method, String paramsJson) {
}
