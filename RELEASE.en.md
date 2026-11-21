# Java DSH Bridge v0.1.0

[简体中文](RELEASE.md) · **English**

The first release provides a standalone JDK 21 SDK and an HTTP/SSE Bridge for local use and trusted callers. The service uses Spring Boot 4.1.3 and Tomcat 11.0.29 and listens on `127.0.0.1:8080` by default.

| Download | Contents |
| --- | --- |
| `java-dsh-bridge-0.1.0.zip` | Executable JAR, runtime dependency list, license, and notices |
| `java-dsh-bridge-sdk-0.1.0.zip` | SDK JAR, SDK POM, parent POM, license, and notices |
| `SHA256SUMS` | SHA-256 hashes for both ZIPs. Run `shasum -a 256 -c SHA256SUMS` in the download directory |

## Run the Bridge

The smoke test targets DSH `0.2.2-rc.2`. With Node.js 24.9+, run `npm install --global @deepseek-ai/dsh@0.2.2-rc.2`. With Python 3.10+, install `deepseek-harness-sdk==0.2.2rc2` in a virtual environment and activate it. Put `java` and `dsh` on `PATH`. Set `DEEPSEEK_API_KEY`, then run the following from the extracted Bridge directory.

```sh
mkdir -p .dsh/home .dsh/workspace
java -jar java-dsh-bridge-0.1.0.jar \
  --dsh.bridge.dsh-home=.dsh/home \
  --dsh.bridge.working-directory=.dsh/workspace
```

```sh
curl -sS http://127.0.0.1:8080/run \
  -H 'Content-Type: application/json' -d '{"text":"Describe DeepSeek Harness in one sentence"}'
```

Send the same JSON to `POST /run/events` for SSE. Use `curl -N` and set `Accept: text/event-stream`. The final event is `result` or `error`. Lines starting with `:heartbeat` are SSE comments. Pass the `sessionId` from the previous JSON response or `result` event to continue the conversation.

The HTTP service defaults to a 30-second initialization timeout and a 5-minute run timeout after initialization. The SSE heartbeat interval is 15 seconds. A detected disconnect or timeout stops Java from waiting. Work already submitted to DSH can continue. Retrying the same session submits a new input. Check the outcome before retrying operations with external side effects. SSE can carry tool data and workspace contents. For remote access, use a controlled gateway, authentication, and a low-privilege service account.

## Install the SDK locally

From the extracted SDK directory, install the parent POM and SDK into your local Maven repository.

```sh
mvn -N -f java-dsh-bridge-parent-0.1.0.pom install
mvn install:install-file -Dfile=java-dsh-bridge-sdk-0.1.0.jar -DpomFile=java-dsh-bridge-sdk-0.1.0.pom
```

Declare `cn.zntime.dsh.bridge:java-dsh-bridge-sdk:0.1.0` and use `DshSdk` with `DshSdkConfig`. The SDK waits indefinitely by default, with a configurable run timeout. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for licensing and attribution.
