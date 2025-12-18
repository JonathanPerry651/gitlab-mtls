load("@rules_java//java:defs.bzl", "java_test")

java_test(
    name = "VersionTest",
    srcs = ["VersionTest.java"],
    deps = [
        "@maven//:junit_junit",
    ],
)

java_test(
    name = "TokenManagerTest",
    srcs = ["TokenManagerTest.java"],
    deps = [
        "//java/src/main/java/com/gitlab/proxy:proxy_lib",
        "@maven//:junit_junit",
    ],
)
