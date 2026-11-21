# 安全策略

**简体中文** · [English](SECURITY.en.md)

Java DSH Bridge 面向本机和受信任调用方。HTTP 接口默认监听 `127.0.0.1`。任何可以访问接口的调用方都可以提交运行请求，或使用已知的会话 ID 访问共享 DSH 进程中的会话。DSH 工具以运行账户的权限执行，SSE 事件可能包含工具输入、工具输出和工作区数据。

请为 Bridge 配置独立的工作目录和 `DSH_HOME`，使用最小权限的运行账户，按需要配置 DSH 的工具和沙箱。需要远程访问时，请先通过受控网关限制访问范围并启用鉴权。

HTTP 等待超时或检测到 SSE 断开后，Java 侧会释放本轮等待。已经提交的 DSH 活动可能继续。重试同一个会话会提交新输入，涉及外部写入时请先确认上次结果。

涉及当前源码的安全问题，请通过 GitHub 仓库的 **Security → Advisories → Report a vulnerability** 私下报告。提供影响范围、Bridge 与 DSH 版本以及最小复现步骤。发布仓库后，维护者可在仓库设置中启用 [Private vulnerability reporting](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/configure-vulnerability-reporting/configure-for-a-repository)。
