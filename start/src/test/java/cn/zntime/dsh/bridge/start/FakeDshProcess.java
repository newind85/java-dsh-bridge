package cn.zntime.dsh.bridge.start;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;

import static java.nio.charset.StandardCharsets.UTF_8;

/** 在独立进程中提供可暂停和重启的 DSH stdio 测试协议。 */
public final class FakeDshProcess {
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String MESSAGE_ID = "fixture-message";
    private static final String COMMAND_ID = "fixture-command";
    static final String HOLD = "fixture:hold";
    static final String SILENT = "fixture:silent";
    static final String EXIT = "fixture:exit";
    static final String COMMAND = "fixture:command";

    /**
     * 接受 JSON-RPC 请求，用工作目录中的测试文件模拟持久化会话和事件时序。
     *
     * @param args Bridge 添加的 DSH 启动参数
     * @throws IOException 测试协议管道或会话文件不可用
     */
    public static void main(String[] args) throws IOException {
        var sessions = new HashSet<String>();
        try (var input = new BufferedReader(new InputStreamReader(System.in, UTF_8));
             var output = new PrintWriter(System.out, true, UTF_8)) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonNode request = JSON.readTree(line);
                String method = request.path("method").stringValue();
                JsonNode params = request.path("params");
                String sessionId = params.path("sessionId").stringValue("");
                ObjectNode response = frame();
                response.set("id", request.path("id"));
                ObjectNode result = response.putObject("result");
                switch (method) {
                    case "initialize" -> {
                        if (Files.exists(Path.of("stall-initialize"))) {
                            continue;
                        }
                        result.putObject("serverInfo").put("name", "deepseek-harness-sdk-runtime")
                                .put("version", "fixture");
                    }
                    case "session/query" -> {
                        sessions.add(sessionId);
                        Files.writeString(Path.of(sessionId), "persisted");
                        String text = params.path("contentBlocks").path(0).path("text").stringValue();
                        switch (text) {
                            case COMMAND -> {
                                emitCommand(sessionId, output);
                                result.put("kind", "command").putObject("execution").put("commandId", COMMAND_ID);
                            }
                            case EXIT -> {
                                result.put("kind", "prompt").put("messageId", MESSAGE_ID);
                                output.println(response);
                                return;
                            }
                            default -> {
                                result.put("kind", "prompt").put("messageId", MESSAGE_ID);
                                emitTurn(sessionId, text, output);
                            }
                        }
                    }
                    case "session/wait" -> {
                        if (!sessions.contains(sessionId)) {
                            error(response, "unknown SDK session: " + sessionId);
                        }
                    }
                    case "session/resume" -> {
                        if (Files.exists(Path.of(sessionId))) {
                            sessions.add(sessionId);
                            Files.writeString(Path.of("resumed-" + sessionId), "resumed");
                        } else {
                            error(response, "session \"" + sessionId + "\" not found");
                        }
                    }
                    case "shutdown" -> { }
                    default -> throw new IllegalArgumentException("Unexpected fixture method: " + method);
                }
                output.println(response);
                if (method.equals("shutdown")) {
                    return;
                }
            }
        }
    }

    /**
     * 发出普通活动的入队通知，并根据测试指令立即或延迟结束活动。
     *
     * @param sessionId 根会话 ID
     * @param text 测试输入
     * @param output 协议输出管道
     * @throws IOException 无法写入同步标记
     */
    private static void emitTurn(String sessionId, String text, PrintWriter output) throws IOException {
        if (SILENT.equals(text)) {
            Files.writeString(Path.of("silent-" + sessionId), "received");
            return;
        }
        ObjectNode started = JSON.createObjectNode();
        started.putArray("inserted").addObject().put("id", MESSAGE_ID);
        // 通知先于回执到达，验证 Java 在提交请求前登记订阅。
        output.println(event(sessionId, "agent/inbox/spliced", started));
        if (HOLD.equals(text)) {
            Thread.startVirtualThread(() -> {
                try {
                    long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
                    while (System.nanoTime() < deadline) {
                        if (Files.exists(Path.of("release-" + sessionId))) {
                            finishTurn(sessionId, text, output);
                            return;
                        }
                        Thread.sleep(Duration.ofMillis(20));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } else {
            finishTurn(sessionId, text, output);
        }
    }

    /**
     * 写入普通活动的回复和空闲通知。
     *
     * @param sessionId 根会话 ID
     * @param text 合成回复所用的测试输入
     * @param output 协议输出管道
     */
    private static void finishTurn(String sessionId, String text, PrintWriter output) {
        ObjectNode reply = JSON.createObjectNode();
        reply.putObject("message").putArray("content").addObject().put("type", "text").put("text", "收到：" + text);
        output.println(event(sessionId, "assistant/message", reply));
        ObjectNode ended = JSON.createObjectNode();
        ended.putObject("reason").put("kind", "completed");
        output.println(event(sessionId, "turn/end", ended));
        ObjectNode idle = frame().put("method", "session.status");
        idle.putObject("params").put("sessionId", sessionId).put("status", "idle");
        output.println(idle);
    }

    /**
     * 发出命令生命周期，覆盖完成通知先于 command/done 的顺序。
     *
     * @param sessionId 根会话 ID
     * @param output 协议输出管道
     */
    private static void emitCommand(String sessionId, PrintWriter output) {
        ObjectNode identity = JSON.createObjectNode().put("commandId", COMMAND_ID);
        output.println(event(sessionId, "command/run", identity));
        ObjectNode completed = frame().put("method", "session.query.completed");
        completed.putObject("params").put("sessionId", sessionId).put("commandId", COMMAND_ID)
                .put("finalResponse", "command result").put("finishReason", "completed");
        output.println(completed);
        output.println(event(sessionId, "command/done", identity));
    }

    /**
     * 用上游 SDK 的错误结构替换原来的成功回执。
     *
     * @param response 带有 JSON-RPC 请求 ID 的响应
     * @param message 上游会话查找错误消息
     */
    private static void error(ObjectNode response, String message) {
        response.remove("result");
        response.putObject("error").put("code", -32000).put("message", message);
    }

    /** @return 带 JSON-RPC 协议版本的空帧 */
    private static ObjectNode frame() {
        return JSON.createObjectNode().put("jsonrpc", "2.0");
    }

    /**
     * 将一条会话事件封装为 JSON-RPC 通知。
     *
     * @param sessionId 通知对应的会话
     * @param type 事件类型
     * @param data 事件数据
     * @return 可写入 stdout 的通知帧
     */
    private static ObjectNode event(String sessionId, String type, ObjectNode data) {
        ObjectNode frame = frame().put("method", "session.event");
        frame.putObject("params").put("sessionId", sessionId).putObject("event").put("type", type).set("data", data);
        return frame;
    }
}
