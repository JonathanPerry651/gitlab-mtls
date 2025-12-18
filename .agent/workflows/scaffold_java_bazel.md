---
description: Scaffold a new hermetic Java 25 Bazel repository
---

This workflow creates a hermetic Java 25 project structure using Bazel (Bzlmod). It explicitly configures the toolchain to handle missing local JDKs by registering a bootstrap runtime.

1.  **Create `.bazelversion`**:
    ```bash
    echo "7.4.0" > .bazelversion
    ```

2.  **Create `MODULE.bazel`**:
    Use `rules_java` with a manually configured JDK 25 toolchain (Azul Zulu build) to ensure hermeticity even without local Java.
    ```python
    module(
        name = "java_template",
        version = "0.1.0",
    )

    bazel_dep(name = "rules_java", version = "7.6.1")
    bazel_dep(name = "rules_jvm_external", version = "6.8")
    bazel_dep(name = "rules_pkg", version = "0.10.1")
    bazel_dep(name = "platforms", version = "0.0.10")

    # Oracle JDK 25 Configuration
    http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

    http_archive(
        name = "jdk25",
        urls = ["https://cdn.azul.com/zulu/bin/zulu25.30.17-ca-jdk25.0.1-linux_x64.tar.gz"],
        integrity = "sha256-Rxs+Yr3/rtJ+NwBdhC2GOfENJEzM4cfN6/erzgbIMT4=",
        strip_prefix = "zulu25.30.17-ca-jdk25.0.1-linux_x64",
        build_file_content = """
load("@rules_java//java:defs.bzl", "java_runtime")
load("@bazel_tools//tools/jdk:default_java_toolchain.bzl", "default_java_toolchain")

package(default_visibility = ["//visibility:public"])

java_runtime(
    name = "runtime",
    srcs = glob(["**"]),
    java_home = ".",
    java = "bin/java", # Verify path inside tarball
)

default_java_toolchain(
    name = "toolchain_impl",
    source_version = "25",
    target_version = "25",
    java_runtime = ":runtime",
)

toolchain(
    name = "toolchain",
    toolchain = ":toolchain_impl",
    toolchain_type = "@bazel_tools//tools/jdk:toolchain_type",
    exec_compatible_with = ["@platforms//os:linux", "@platforms//cpu:x86_64"],
    target_compatible_with = ["@platforms//os:linux", "@platforms//cpu:x86_64"],
)

# Required for bootstrapping without local Java
toolchain(
    name = "bootstrap_runtime_toolchain",
    toolchain = ":runtime",
    toolchain_type = "@bazel_tools//tools/jdk:bootstrap_runtime_toolchain_type",
    exec_compatible_with = ["@platforms//os:linux", "@platforms//cpu:x86_64"],
    target_compatible_with = ["@platforms//os:linux", "@platforms//cpu:x86_64"],
)
""",
    )
    register_toolchains("@jdk25//:toolchain", "@jdk25//:bootstrap_runtime_toolchain")

    # Maven Dependencies
    maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
    maven.install(
        artifacts = [
            "com.fasterxml.jackson.core:jackson-databind:2.16.1",
            "junit:junit:4.13.2",
        ],
        resolver = "maven",
        name = "maven",
        lock_file = "//:maven_install.json",
    )
    use_repo(maven, "maven")
    ```

3.  **Create Root `BUILD`**:
    ```python
    # Empty root build file
    ```

4.  **Create Directory Structure**:
    ```bash
    mkdir -p src/main/java/com/example
    ```

5.  **Create Example `BUILD`**:
    In `src/main/java/com/example/BUILD`:
    ```python
    load("@rules_java//java:defs.bzl", "java_binary")

    java_binary(
        name = "Main",
        srcs = ["Main.java"],
        deps = ["@maven//:com_fasterxml_jackson_core_jackson_databind"],
    )
    ```

6.  **Build**:
    ```bash
    # Note: Requires repo_env pointing to JDK if system Java is missing for Coursier
    # Note: Requires repo_env pointing to JDK if system Java is missing for Coursier
    bazel build //src/main/java/com/example:Main
    ```
