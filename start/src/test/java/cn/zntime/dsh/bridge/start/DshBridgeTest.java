package cn.zntime.dsh.bridge.start;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 HTTP 服务和本地模拟子进程验证桥接链路。 */
@Timeout(30)
class DshBridgeTest {
    private static final JsonMapper JSON = JsonMapper.shared();
    private static ServletWebServerApplicationContext application;
    private static URI endpoint;
    private static Path workingDirectory;
    private static HttpClient http;

    /**
     * 启动使用随机端口的 Bridge，将 DSH 启动命令指向测试子进程。
     *
     * @param workspace 测试专用的临时目录
     */
    @BeforeAll
    static void start(@TempDir Path workspace) {
        workingDirectory = workspace;
        application = launch(workspace, "30s", "8s");
        endpoint = URI.create("http://127.0.0.1:" + application.getWebServer().getPort());
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    /**
     * 启动独立应用和按需创建的测试子进程。
     *
     * @param workspace 测试会话和进程控制文件所在的目录
     * @param initializeTimeout 握手期限
     * @param runTimeout 执行期限
     * @return 已启动的本地 HTTP 应用
     */
    private static ServletWebServerApplicationContext launch(Path workspace, String initializeTimeout,
                                                              String runTimeout) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // 直接传递测试运行时的类路径，避免依赖 Surefire 的引导 JAR。
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return (ServletWebServerApplicationContext) SpringApplication.run(Application.class,
                "--server.address=127.0.0.1", "--server.port=0", "--spring.profiles.active=test",
                "--dsh.app.path=", "--dsh.bridge.working-directory=" + workspace,
                "--dsh.bridge.dsh-home=" + workspace, "--dsh.bridge.run-timeout=" + runTimeout,
                "--dsh.bridge.initialize-timeout=" + initializeTimeout, "--dsh.bridge.sse-heartbeat-interval=100ms",
                "--dsh.bridge.command[0]=" + java, "--dsh.bridge.command[1]=-cp",
                "--dsh.bridge.command[2]=" + classpath, "--dsh.bridge.command[3]=" + FakeDshProcess.class.getName());
    }

    /** 关闭应用上下文以及它管理的 DSH 子进程。 */
    @AfterAll
    static void stop() {
        if (application != null) {
            application.close();
        }
        if (http != null) {
            http.close();
        }
    }

    /**
     * 验证同步结果，以及用同一个会话继续接收 SSE 通知和最终结果。
     *
     * @throws IOException HTTP 请求失败时抛出
     * @throws InterruptedException 等待响应被中断时抛出
     */
    @Test
    void runsAndContinuesThroughSse() throws IOException, InterruptedException {
        var first = post("/run", "application/json", "{\"text\":\"第一轮\"}");
        assertEquals(200, first.statusCode(), first.body());
        JsonNode result = JSON.readTree(first.body());
        String sessionId = result.path("sessionId").stringValue("");
        assertTrue(sessionId.matches("session-[0-9a-f]{32}"));
        assertEquals("收到：第一轮", result.path("text").stringValue());
        assertEquals("completed", result.path("finishReason").stringValue());

        String request = JSON.createObjectNode().put("sessionId", sessionId).put("text", "第二轮").toString();
        var stream = post("/run/events", "text/event-stream", request);
        assertEquals(200, stream.statusCode(), stream.body());
        assertTrue(stream.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        String body = stream.body().replace("\r\n", "\n");
        assertTrue(body.contains("event:session.event\n"), body);
        String data = body.lines().dropWhile(line -> !line.equals("event:result"))
                .skip(1).filter(line -> line.startsWith("data:")).findFirst().orElseThrow();
        JsonNode streamedResult = JSON.readTree(data.substring("data:".length()));
        assertEquals(sessionId, streamedResult.path("sessionId").stringValue());
        assertEquals("收到：第二轮", streamedResult.path("text").stringValue());
    }

    /**
     * 验证空白输入和非法会话 ID 的 HTTP 状态及 SSE 错误事件。
     *
     * @throws IOException HTTP 请求失败时抛出
     * @throws InterruptedException 等待响应被中断时抛出
     */
    @Test
    void rejectsInvalidInput() throws IOException, InterruptedException {
        assertEquals(400, post("/run", "application/json", "{\"text\":\" \"}").statusCode());
        String request = "{\"text\":\"hello\",\"sessionId\":\"other\"}";
        var invalidSession = post("/run", "application/json", request);
        assertEquals(400, invalidSession.statusCode(), invalidSession.body());
        assertEquals("INVALID_SESSION_ID", JSON.readTree(invalidSession.body()).path("code").stringValue());

        var stream = post("/run/events", "text/event-stream", request);
        assertEquals(200, stream.statusCode(), stream.body());
        assertTrue(stream.body().lines().anyMatch("event:error"::equals), stream.body());
        assertTrue(stream.body().contains("\"code\":\"INVALID_SESSION_ID\""), stream.body());
    }

    /** 验证握手超时返回稳定的错误码，下一次调用可重新初始化。 */
    @Test
    void reportsInitializeTimeoutAndCanRetry(@TempDir Path workspace) throws IOException, InterruptedException {
        Path stall = Files.createFile(workspace.resolve("stall-initialize"));
        try (var app = launch(workspace, "2s", "5s")) {
            URI address = URI.create("http://127.0.0.1:" + app.getWebServer().getPort());
            var request = request(address, "/run", "application/json", payload("hello", null));
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertStatus(504, "STARTUP_TIMEOUT", response);

            Files.delete(stall);
            var retry = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, retry.statusCode(), retry.body());
        }
    }

    /** 验证正常路径之外的等待期限，通过 HTTP 和 SSE 分别返回执行超时。 */
    @Test
    void timesOutSilentRun(@TempDir Path workspace) throws IOException, InterruptedException {
        try (var app = launch(workspace, "30s", "600ms")) {
            URI address = URI.create("http://127.0.0.1:" + app.getWebServer().getPort());
            String body = payload(FakeDshProcess.SILENT, null);
            var response = http.send(request(address, "/run", "application/json", body),
                    HttpResponse.BodyHandlers.ofString());
            assertStatus(504, "TIMEOUT", response);
            var stream = http.send(request(address, "/run/events", "text/event-stream", body),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, stream.statusCode(), stream.body());
            assertTrue(stream.body().contains("event:error"), stream.body());
            assertTrue(stream.body().contains("\"code\":\"TIMEOUT\""), stream.body());
        }
    }

    /** 验证 DSH 在运行中退出后返回 502，新进程可从持久化状态恢复原有会话。 */
    @Test
    void reportsProcessExitAndResumesPersistedSession() throws IOException, InterruptedException {
        String sessionId = newSession();
        var failed = post("/run", "application/json", payload(FakeDshProcess.EXIT, sessionId));
        assertStatus(502, "UPSTREAM_ERROR", failed);
        var resumed = post("/run", "application/json", payload("recovered", sessionId));
        assertEquals(200, resumed.statusCode(), resumed.body());
        assertEquals(sessionId, JSON.readTree(resumed.body()).path("sessionId").stringValue());
        assertTrue(Files.exists(workingDirectory.resolve("resumed-" + sessionId)));
    }

    /** 验证合法格式的未知会话返回 404，且命令以匹配的完成事件结算。 */
    @Test
    void reportsUnknownSessionAndCollectsCommandResult() throws IOException, InterruptedException {
        String unknown = "session-00000000000000000000000000000000";
        assertStatus(404, "SESSION_NOT_FOUND", post("/run", "application/json", payload("hello", unknown)));
        var command = post("/run", "application/json", payload(FakeDshProcess.COMMAND, null));
        assertEquals(200, command.statusCode(), command.body());
        assertEquals("command result", JSON.readTree(command.body()).path("text").stringValue());
    }

    /** 验证首条事件实时到达，同会话互斥且并行会话的通知不会混入。 */
    @Test
    void streamsIncrementallyAndIsolatesConcurrentSessions() throws IOException, InterruptedException {
        try (var first = openStream(FakeDshProcess.HOLD, null);
             var second = openStream(FakeDshProcess.HOLD, null)) {
            String firstId = nextData(first, "session.event").path("sessionId").stringValue();
            String secondId = nextData(second, "session.event").path("sessionId").stringValue();
            assertNotEquals(firstId, secondId);
            assertStatus(409, "SESSION_BUSY", post("/run", "application/json", payload("overlap", firstId)));

            Files.createFile(workingDirectory.resolve("release-" + secondId));
            String secondTail = String.join("\n", second.lines().toList());
            assertTrue(secondTail.contains("event:result"), secondTail);
            assertTrue(secondTail.contains(secondId), secondTail);
            assertFalse(secondTail.contains(firstId), secondTail);

            Files.createFile(workingDirectory.resolve("release-" + firstId));
            String firstTail = String.join("\n", first.lines().toList());
            assertTrue(firstTail.contains("event:result"), firstTail);
            assertTrue(firstTail.contains(firstId), firstTail);
            assertFalse(firstTail.contains(secondId), firstTail);
        }
    }

    /** 验证静默期间的心跳和主动断开后的 Java 会话释放。 */
    @Test
    void heartbeatDetectsSilentClientDisconnect() throws IOException, InterruptedException {
        String sessionId = newSession();
        try (var stream = openStream(FakeDshProcess.SILENT, sessionId)) {
            assertEquals(":heartbeat", stream.readLine());
            assertEquals("", stream.readLine());
            Path accepted = workingDirectory.resolve("silent-" + sessionId);
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!Files.exists(accepted) && System.nanoTime() < deadline) {
                Thread.sleep(Duration.ofMillis(20));
            }
            assertTrue(Files.exists(accepted), "DSH must receive the run before the client disconnects");
        }

        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        do {
            var next = post("/run", "application/json", payload("after disconnect", sessionId));
            if (next.statusCode() == 200) {
                assertEquals(sessionId, JSON.readTree(next.body()).path("sessionId").stringValue());
                return;
            }
            assertStatus(409, "SESSION_BUSY", next);
            Thread.sleep(Duration.ofMillis(50));
        } while (System.nanoTime() < deadline);
        fail("Idle SSE worker retained the session after client disconnect");
    }

    /** @return 使用正常 HTTP 请求新建的会话标识 */
    private static String newSession() throws IOException, InterruptedException {
        var response = post("/run", "application/json", payload("first", null));
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body()).path("sessionId").stringValue();
    }

    /**
     * 在响应体仍可继续写入时返回读取器，由调用方管理连接生命周期。
     *
     * @param text 测试输入
     * @param sessionId 可选的会话 ID
     * @return 流式 SSE 正文读取器
     */
    private static BufferedReader openStream(String text, String sessionId) throws IOException, InterruptedException {
        var response = http.send(request(endpoint, "/run/events", "text/event-stream", payload(text, sessionId)),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        return new BufferedReader(new InputStreamReader(response.body(), UTF_8));
    }

    /**
     * 从流中仅读取直到指定的事件，跳过心跳和其他事件。
     *
     * @param reader 流式正文读取器
     * @param event 期望事件类型
     * @return 该事件的数据
     */
    private static JsonNode nextData(BufferedReader reader, String event) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.equals("event:" + event)) {
                String data = reader.readLine();
                assertTrue(data != null && data.startsWith("data:"));
                return JSON.readTree(data.substring("data:".length()));
            }
        }
        return fail("SSE ended before " + event);
    }

    /** 断言同步接口响应的 HTTP 状态和业务错误码。 */
    private static void assertStatus(int status, String error, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(error, JSON.readTree(response.body()).path("code").stringValue());
    }

    /** 按请求契约构造 JSON，创建会话时省略 ID。 */
    private static String payload(String text, String sessionId) {
        var data = JSON.createObjectNode().put("text", text);
        if (sessionId != null) {
            data.put("sessionId", sessionId);
        }
        return data.toString();
    }

    /** 发送请求并读取完整响应，用于普通 JSON 和终态断言。 */
    private static HttpResponse<String> post(String path, String accept, String body)
            throws IOException, InterruptedException {
        return http.send(request(endpoint, path, accept, body), HttpResponse.BodyHandlers.ofString());
    }

    /** 构造固定请求期限的本地 HTTP 请求。 */
    private static HttpRequest request(URI address, String path, String accept, String body) {
        return HttpRequest.newBuilder(address.resolve(path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
