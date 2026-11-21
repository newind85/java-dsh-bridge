# 贡献指南

**简体中文** · [English](CONTRIBUTING.en.md)

## Fork 并创建分支

在 [Java DSH Bridge 仓库首页](https://github.com/newind85/java-dsh-bridge) 点击 **Fork**，将仓库复制到自己的 GitHub 账号。把下面的 `YOUR_USERNAME` 替换成你的 GitHub 用户名，然后从上游 `main` 创建工作分支。

```sh
git clone https://github.com/YOUR_USERNAME/java-dsh-bridge.git
cd java-dsh-bridge
git remote add upstream https://github.com/newind85/java-dsh-bridge.git
git fetch upstream main
git switch --no-track -c fix/example upstream/main
```

## 本地测试

安装 JDK 21，在仓库根目录运行 Maven Wrapper。Linux 和 macOS 使用以下命令。

```sh
./mvnw -B -ntp clean verify
```

Windows PowerShell 使用以下命令。

```powershell
.\mvnw.cmd -B -ntp clean verify
```

集成测试用本地模拟 DSH 子进程验证 HTTP、SSE 和 stdio 通信，无需安装 DSH 或配置模型凭据。涉及 DSH 协议时，请附上验证过的上游版本，并用脱敏的 JSON-RPC 交互补充回归用例。修改 HTTP 接口或配置时，同步更新[中文 README](README.md) 和[英文 README](README.en.md) 的示例及说明。

## 提交 Pull Request

1. 暂存要提交的文件、创建提交，并推送到你自己的 Fork。将 `path/to/changed-file` 换成实际文件路径。

   ```sh
   git add -- path/to/changed-file
   git commit -m "Describe the change"
   git push -u origin fix/example
   ```

2. 回到上游仓库，点击 **Pull requests → New pull request → compare across forks**。将 **base** 设为 `newind85/java-dsh-bridge:main`，将 **head** 设为你 Fork 下的 `fix/example` 分支。
3. 按模板说明使用场景、变更范围、测试命令和结果。有相关 Issue 时附上链接。需要修改时，继续提交并推送到同一分支，GitHub 会更新原 PR。
4. 在 PR 页面查看 `verify (ubuntu-latest)` 和 `verify (macos-latest)`。首次贡献者的工作流可能需要维护者授权运行，两个检查通过后 PR 才能合并。

## 验证与打包

Linux CI 使用固定版本的 Python DSH 安装包和本地模型模拟服务验证成品 JAR。也可在本地用同一脚本验证 npm 版或 Python 版 DSH。`--dsh` 可传入绝对路径，省略时通过 `PATH` 查找。

```sh
./mvnw -B -ntp clean verify
python3 scripts/smoke-test.py --jar start/target/java-dsh-bridge-0.1.0.jar
python3 scripts/package-release.py
(cd release/v0.1.0 && shasum -a 256 -c SHA256SUMS)
```

从待发布的源码快照执行上述步骤。打包脚本输出 Bridge ZIP、SDK ZIP 和校验文件，两个 JAR 以及 ZIP 均包含项目许可证和声明。检查版本、成品依赖和 DSH 兼容性，发布说明见 [v0.1.0](RELEASE.md)。发布前复核首提交的文件清单、作者身份和第三方署名。
