# Security policy

[简体中文](SECURITY.md) · **English**

Java DSH Bridge is intended for local use and trusted clients. The HTTP service listens on `127.0.0.1` by default. Anyone who can reach it can submit runs or access a session in the shared DSH process using a known session ID. DSH tools run with the permissions of the service account, and SSE events may contain tool inputs, tool outputs, and workspace data.

Give the Bridge a dedicated working directory and `DSH_HOME`. Run it under an account with only the privileges it needs, and configure DSH tools and sandboxing for your deployment. For remote access, put the Bridge behind a gateway that authenticates callers and restricts access.

An HTTP execution timeout or detected SSE disconnect releases the Java-side wait. Work already submitted to DSH can continue. Retrying a session submits a new input. Check the previous outcome before retrying an operation with external side effects.

Report security issues in the current source privately via **Security → Advisories → Report a vulnerability** in the GitHub repository. Include the scope of the impact, the Bridge and DSH versions, and minimal reproduction steps. After publishing the repository, maintainers can enable [private vulnerability reporting](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/configure-vulnerability-reporting/configure-for-a-repository) in its settings.
