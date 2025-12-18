load("@rules_java//java:defs.bzl", "java_binary", "java_library")

java_library(
    name = "proxy_lib",
    srcs = [
        "Config.java",
        "Main.java",
        "ProxyHandler.java",
        "TokenManager.java",
    ],
    deps = [
        "@maven//:com_fasterxml_jackson_core_jackson_databind",
        "@maven//:com_fasterxml_jackson_core_jackson_core",
        "@maven//:com_fasterxml_jackson_core_jackson_annotations",
        "@maven//:io_prometheus_simpleclient",
        "@maven//:io_prometheus_simpleclient_httpserver",
        "@maven//:io_prometheus_simpleclient_hotspot",
    ],
    visibility = ["//visibility:public"],
)

java_binary(
    name = "Main",
    runtime_deps = [":proxy_lib"],
    data = ["//:certs"],
    visibility = ["//visibility:public"],
)
