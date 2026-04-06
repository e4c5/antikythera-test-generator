# antikythera-test-generator Configuration Guide

Configuration is loaded from a `generator.yml` file on the classpath, or from a path passed
explicitly to `Settings.loadConfigMap(File)`.

All settings listed in the
[Antikythera core configuration guide](https://github.com/Cloud-Solutions-International/antikythera/blob/main/docs/configurations.md) also apply here.
This document covers only the additional settings specific to test generation.

---

## Target Classes

| Property | Description |
| :--- | :--- |
| `services` | List of fully qualified class names of Spring service classes for which unit tests should be generated. |
| `controllers` | List of fully qualified class names of Spring `@RestController` classes for which API tests should be generated. |

Example:

```yaml
services:
  - com.example.service.OrderService
  - com.example.service.UserService

controllers:
  - com.example.controller.OrderController
```

---

## Test Framework

| Property | Default | Description |
| :--- | :--- | :--- |
| `test_framework` | `junit` | Test framework for generated test classes. Supported values: `junit` (JUnit 5), `testng`. |
| `mock_with` | `Mockito` | Mocking framework referenced in generated test source (e.g. `@Mock`, `@InjectMocks` annotations and `when(...).thenReturn(...)` calls). |

---

## Test Class Customisation

| Property | Default | Description |
| :--- | :--- | :--- |
| `base_test_class` | `TestHelper` (API), none (unit) | Fully qualified name of a class that every generated test class should extend. Applies to **both** unit tests (via `services`) and API tests (via `controllers`). For unit tests the class must already exist under `src/test/java`; Antikythera parses it to discover shared mocks and to execute its `setUpBase()` method. For API tests, if this setting is absent, the generated class extends the built-in `TestHelper`. See the [Base Test Class](../README.md#base-test-class) section of the README for full details. |
| `extra_imports` | — | List of additional fully qualified type names added as imports to every generated test class. |
| `test_privates` | `false` | When `true`, private methods are included in test generation alongside package-visible and public methods. |

Example:

```yaml
base_test_class: com.example.test.BaseTest

extra_imports:
  - com.example.test.TestConstants
  - com.example.util.Fixtures

test_privates: false
```

---

## Void Method and Constructor Testing

| Property | Default | Description |
| :--- | :--- | :--- |
| `skip_void_no_side_effects` | `true` | When `true`, no test is emitted for a `void` execution path that produces no detectable side effect. Set to `false` to force a test for every path. Side effects include: `System.out` / `System.err` output, SLF4J log statements, Mockito `when/then` interactions, reachable branching conditions, and thrown exceptions. |
| `generate_constructor_tests` | `false` | When `true`, tests are generated for constructors as well as methods. Useful when constructors perform initialisation with observable side effects (e.g. logging, field validation). |
| `log_appender` | — | Fully qualified name of the `LogAppender` class used to capture and assert on SLF4J log output. Required to enable log-based assertions for void methods; if absent, log assertions are skipped even when log output is observed. See the [Testing Void Methods via Side Effects](../README.md#testing-void-methods-via-side-effects) section of the README for setup instructions. |

---

## Generation Modes and `output_path` Semantics

Antikythera operates in two distinct modes depending on which list is populated in `generator.yml`.
The meaning of `output_path` differs between them.

### Unit Test Mode (`services` only)

Generated tests are written **directly into the existing project** alongside its main sources.
`output_path` must point to the project's existing `src/test/java` directory:

```yaml
services:
  - com.example.service.OrderService

output_path: /path/to/project/src/test/java
```

No Maven project structure, no `pom.xml`, and no `TestHelper.java` are generated.
The tests compile and run as part of the existing project's test suite.

### API Test Mode (`controllers`)

Generated tests are written into a **standalone Maven project**.
`output_path` is still the `src/test/java` directory, but Antikythera automatically derives the
project root by stripping the trailing `src/test/java` components, and places `pom.xml` and the
full Maven directory structure there:

```yaml
controllers:
  - com.example.controller.OrderController

output_path: /path/to/standalone-tests/src/test/java
# → project root derived as: /path/to/standalone-tests/
# → pom.xml written to:      /path/to/standalone-tests/pom.xml
```

`TestHelper.java` (or the class named by `base_test_class`) and `Configurations.java` are also
copied into the standalone project's source tree.

### Mixed Mode (`controllers` + `services`)

Both modes are active simultaneously.  `output_path` serves the API test standalone project.
Unit tests are written to the path derived from `base_path` (replacing `src/main` with `src/test`).

---

## API Test Generation

These settings are used when generating REST API tests for `@RestController` classes.  When present
they are written into `src/test/resources/testdata/qa/Url.properties` so that the generated
RESTAssured tests can resolve the base URL at runtime.

| Property | Description |
| :--- | :--- |
| `application.host` | Base URL of the running application (e.g. `http://localhost:8080`). |
| `application.version` | API version string appended to request paths where applicable. |

Example:

```yaml
application:
  host: http://localhost:8080
  version: v1
```

---

## Complete Example

```yaml
# ── Core settings ────────────────────────────────────────────────
base_package: com.example
base_path: /home/dev/myproject/src/main/java
output_path: /home/dev/myproject/src/test/java

dependencies:
  artifact_ids:
    - com.example:shared-lib:2.1.0
  on_error: log

# ── What to generate ─────────────────────────────────────────────
services:
  - com.example.service.OrderService
  - com.example.service.UserService

controllers:
  - com.example.controller.OrderController

# ── Test framework ────────────────────────────────────────────────
test_framework: junit
mock_with: Mockito

# ── Test class customisation ──────────────────────────────────────
base_test_class: com.example.test.BaseTest
extra_imports:
  - com.example.test.TestConstants
test_privates: false

# ── Void / constructor tests ──────────────────────────────────────
skip_void_no_side_effects: true
generate_constructor_tests: false
log_appender: com.example.test.LogAppender

# ── API test base URL ─────────────────────────────────────────────
application:
  host: http://localhost:8080
  version: v1
```
