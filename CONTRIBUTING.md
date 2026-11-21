# 贡献指南

**简体中文** · [English](CONTRIBUTING.en.md)

使用 JDK 21，在仓库根目录执行 `./mvnw -B -ntp clean verify`。Windows PowerShell 使用 `.\mvnw.cmd -B -ntp clean verify`。集成测试用本地模拟 DSH 子进程验证 HTTP、SSE 和 stdio 通信，无需安装 DSH 或配置模型凭据。

提交 PR 时说明使用场景、变更范围和验证结果。修改 DSH 协议处理时，请附上验证过的上游版本，并用脱敏的 JSON-RPC 交互补充回归用例。修改 HTTP 接口或配置时，同步更新[中文 README](README.md) 和[英文 README](README.en.md) 的示例及说明。

## 验证与打包

Linux CI 使用固定版本的 Python DSH 安装包和本地模型模拟服务验证成品 JAR。也可在本地用同一脚本验证 npm 版或 Python 版 DSH。`--dsh` 可传入绝对路径，省略时通过 `PATH` 查找。

```sh
./mvnw -B -ntp clean verify
python3 scripts/smoke-test.py --jar start/target/java-dsh-bridge-0.1.0.jar
python3 scripts/package-release.py
(cd release/v0.1.0 && shasum -a 256 -c SHA256SUMS)
```

从待发布的源码快照执行上述步骤。打包脚本输出 Bridge ZIP、SDK ZIP 和校验文件，两个 JAR 以及 ZIP 均包含项目许可证和声明。检查版本、成品依赖和 DSH 兼容性，发布说明见 [v0.1.0](RELEASE.md)。发布前复核首提交的文件清单、作者身份和第三方署名。
