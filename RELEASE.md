# Java DSH Bridge v0.1.0

**简体中文** · [English](RELEASE.en.md)

首版提供 JDK 21 独立 Java SDK，以及用于本机或受信任调用方的 HTTP/SSE Bridge。服务使用 Spring Boot 4.1.3 和 Tomcat 11.0.29，默认监听 `127.0.0.1:8080`。

| 下载文件 | 内容 |
| --- | --- |
| `java-dsh-bridge-0.1.0.zip` | 可执行 JAR、运行时依赖清单、许可证和声明 |
| `java-dsh-bridge-sdk-0.1.0.zip` | SDK JAR、SDK POM、父 POM、许可证和声明 |
| `SHA256SUMS` | 两个 ZIP 的 SHA-256，可在下载目录执行 `shasum -a 256 -c SHA256SUMS` |

## 运行 Bridge

兼容性烟测使用 DSH `0.2.2-rc.2`。可用 Node.js 24.9+ 执行 `npm install --global @deepseek-ai/dsh@0.2.2-rc.2`，或在 Python 3.10+ 虚拟环境中安装 `deepseek-harness-sdk==0.2.2rc2` 并激活该环境。确认 `java` 和 `dsh` 均在 `PATH`。配置 `DEEPSEEK_API_KEY` 后，在解压出的 Bridge 目录执行：

```sh
mkdir -p .dsh/home .dsh/workspace
java -jar java-dsh-bridge-0.1.0.jar \
  --dsh.bridge.dsh-home=.dsh/home \
  --dsh.bridge.working-directory=.dsh/workspace
```

```sh
curl -sS http://127.0.0.1:8080/run \
  -H 'Content-Type: application/json' -d '{"text":"用一句话介绍 DeepSeek Harness"}'
```

将同一个 JSON 请求发到 `POST /run/events` 可接收 SSE，使用 `curl -N` 并添加 `Accept: text/event-stream`。终态为 `result` 或 `error`，期间的 `:heartbeat` 行是 SSE 注释。把上一轮 `result` 或同步 JSON 中的 `sessionId` 带到新请求，可继续同一会话。

HTTP 服务默认等待握手最多 30 秒，随后等待本轮执行最多 5 分钟。SSE 心跳间隔默认 15 秒。Bridge 检测到断连或等待超时后会释放 Java 侧等待，已提交给 DSH 的操作可能继续。同会话重发会提交新输入，涉及外部写入时需先确认执行结果。SSE 可能携带工具调用内容和工作区数据。远程接入前需配置受控网关、鉴权和最小权限运行账户。

## 本地安装 SDK

在解压出的 SDK 目录中，用 Maven 将父 POM 和 SDK 安装到本机仓库：

```sh
mvn -N -f java-dsh-bridge-parent-0.1.0.pom install
mvn install:install-file -Dfile=java-dsh-bridge-sdk-0.1.0.jar -DpomFile=java-dsh-bridge-sdk-0.1.0.pom
```

业务工程可引用 `cn.zntime.dsh.bridge:java-dsh-bridge-sdk:0.1.0`，使用 `DshSdk` 与 `DshSdkConfig`。SDK 默认运行等待不限时，可通过配置指定执行期限。项目许可和署名见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。
