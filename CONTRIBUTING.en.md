# Contributing

[简体中文](CONTRIBUTING.md) · **English**

## Fork and create a branch

Open the [Java DSH Bridge repository](https://github.com/newind85/java-dsh-bridge) and click **Fork** to create a copy under your GitHub account. Replace `YOUR_USERNAME` with your GitHub username, then create a branch from upstream `main`.

```sh
git clone https://github.com/YOUR_USERNAME/java-dsh-bridge.git
cd java-dsh-bridge
git remote add upstream https://github.com/newind85/java-dsh-bridge.git
git fetch upstream main
git switch --no-track -c fix/example upstream/main
```

## Run tests locally

Install JDK 21 and run the Maven Wrapper from the repository root. On Linux and macOS, run the following command.

```sh
./mvnw -B -ntp clean verify
```

In Windows PowerShell, run the following command.

```powershell
.\mvnw.cmd -B -ntp clean verify
```

The integration tests use a local mock DSH process to exercise HTTP, SSE, and stdio. You can run them without a DSH installation or model credentials. For DSH protocol changes, include the upstream version you tested and add a regression test with a redacted JSON-RPC exchange. When changing HTTP endpoints or configuration, update the examples and descriptions in both the [English README](README.en.md) and the [Chinese README](README.md).

## Open a pull request

1. Stage your changes, create a commit, and push it to your fork. Replace `path/to/changed-file` with the path to a file you changed.

   ```sh
   git add -- path/to/changed-file
   git commit -m "Describe the change"
   git push -u origin fix/example
   ```

2. In the upstream repository, open **Pull requests → New pull request → compare across forks**. Set the **base** to `newind85/java-dsh-bridge:main` and the **head** to the `fix/example` branch in your fork.
3. Use the template to describe the use case, what changed, and the test commands and results. Link the related issue if there is one. To address feedback, commit and push to the same branch. GitHub updates the existing pull request.
4. Check `verify (ubuntu-latest)` and `verify (macos-latest)` on the pull request. Workflows for first-time contributors may need a maintainer's approval to run. Both checks must pass before the pull request can be merged.

## Verify and package a release

Linux CI checks the executable JAR with a pinned Python DSH distribution and a local model stub. Use the same script with a local npm or Python installation. Pass an absolute executable path with `--dsh`, or omit it for `PATH` lookup.

```sh
./mvnw -B -ntp clean verify
python3 scripts/smoke-test.py --jar start/target/java-dsh-bridge-0.1.0.jar
python3 scripts/package-release.py
(cd release/v0.1.0 && shasum -a 256 -c SHA256SUMS)
```

Run these steps from the source snapshot you intend to release. The packaging script produces a Bridge ZIP, an SDK ZIP, and SHA-256 checksums. Both JARs and ZIPs include the project license and notices. Check the version, packaged dependencies, and DSH compatibility against the [v0.1.0 release notes](RELEASE.en.md). Review the initial commit's file list, author identity, and third-party attribution before publishing.
