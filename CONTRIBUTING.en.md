# Contributing

[简体中文](CONTRIBUTING.md) · **English**

Use JDK 21 and run `./mvnw -B -ntp clean verify` from the repository root. In Windows PowerShell, use `.\mvnw.cmd -B -ntp clean verify`. The integration tests use a local mock DSH process to exercise HTTP, SSE, and stdio. You can run them without a DSH installation or model credentials.

In your pull request, describe the use case, what changed, and how you verified it. For DSH protocol changes, include the upstream version you tested and add a regression test with a redacted JSON-RPC exchange. When changing HTTP endpoints or configuration, update the examples and descriptions in both the [English README](README.en.md) and the [Chinese README](README.md).

## Verify and package a release

Linux CI checks the executable JAR with a pinned Python DSH distribution and a local model stub. Use the same script with a local npm or Python installation. Pass an absolute executable path with `--dsh`, or omit it for `PATH` lookup.

```sh
./mvnw -B -ntp clean verify
python3 scripts/smoke-test.py --jar start/target/java-dsh-bridge-0.1.0.jar
python3 scripts/package-release.py
(cd release/v0.1.0 && shasum -a 256 -c SHA256SUMS)
```

Run these steps from the source snapshot you intend to release. The packaging script produces a Bridge ZIP, an SDK ZIP, and SHA-256 checksums. Both JARs and ZIPs include the project license and notices. Check the version, packaged dependencies, and DSH compatibility against the [v0.1.0 release notes](RELEASE.en.md). Review the initial commit's file list, author identity, and third-party attribution before publishing.
