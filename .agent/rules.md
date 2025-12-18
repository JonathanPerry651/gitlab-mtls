# Workspace Rules

## Bazel Style

### 1. Target Naming (`java_binary` & `java_test`)
*   **Rule**: `java_binary` and `java_test` targets MUST be named exactly after the entrypoint/test class.
*   **Implementation**: Name the target the same as the class name (e.g., `Main`, `VersionTest`) and OMIT the `main_class` or `test_class` attribute. Bazel defaults these to the target name.
*   **Why**: Simplifies configuration and convention over configuration.

### 2. Explicit Source Lists
*   **Rule**: Do NOT use `glob()` in `srcs` attributes for Java rules.
*   **Implementation**: Explicitly list every Java source file.
*   **Why**: Improves cache invalidation, readability, and prevents accidental inclusion.
