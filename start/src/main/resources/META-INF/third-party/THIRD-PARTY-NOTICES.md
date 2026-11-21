# Runtime dependencies / 运行时依赖

The executable Bridge includes unmodified upstream dependency JARs. Their embedded licenses and notices remain in `BOOT-INF/lib/` in the executable JAR. The release ZIP also includes `DEPENDENCIES.txt` with the exact packaged versions. DeepSeek Harness runs separately.

可执行 Bridge 内含上游原始依赖 JAR，其许可和声明随 JAR 保留在 `BOOT-INF/lib/`。ZIP 中的 `DEPENDENCIES.txt` 列出实际打包的版本。DeepSeek Harness 由用户单独安装和运行。

| Components / 组件 | License and source / 许可证与源码 |
| --- | --- |
| Logback Classic and Core `1.5.38` | Distributed under [EPL-2.0](EPL-2.0.txt). See the [upstream license and copyright notice](LOGBACK-LICENSE.txt). Source archives are available for [Classic](https://repo.maven.apache.org/maven2/ch/qos/logback/logback-classic/1.5.38/logback-classic-1.5.38-sources.jar) and [Core](https://repo.maven.apache.org/maven2/ch/qos/logback/logback-core/1.5.38/logback-core-1.5.38-sources.jar). |
| Jakarta Annotations API `3.0.0` | Distributed under [EPL-2.0](EPL-2.0.txt). See the `META-INF/NOTICE.md` in its JAR and the [source archive](https://repo.maven.apache.org/maven2/jakarta/annotation/jakarta.annotation-api/3.0.0/jakarta.annotation-api-3.0.0-sources.jar). |
| Jakarta Validation API `3.1.1`, JBoss Logging `3.6.3.Final`, JSpecify `1.0.1`, SnakeYAML `2.6` | Apache-2.0. The release includes the Apache 2.0 license. The upstream projects provide [Jakarta Validation](https://github.com/jakartaee/validation), [JBoss Logging](https://github.com/jboss-logging/jboss-logging), [JSpecify](https://github.com/jspecify/jspecify), and [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml/src) source and attribution. |
