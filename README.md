Antikythera Test Generator
==========================

**Automated Unit, Integration and API Test Generation for Java/Spring Applications**

`antikythera-test-generator` is a Maven module built on top of the [Antikythera](https://github.com/Cloud-Solutions-International/antikythera)
core library. It applies Antikythera's Java AST evaluation and expression engine to automatically produce
test suites — JUnit/TestNG unit tests, Mockito-backed service tests, and RESTAssured API tests — with minimal
manual effort.

---

## What It Does

### Unit Test Generation
Generates JUnit 5 tests for Spring service classes:

- **Full Branch Coverage**: Iteratively executes all code paths (up to 16 branches per method) and generates a dedicated test for each.
- **Mockito Scaffolding**: Automatically identifies injected dependencies and emits `@Mock`, `@InjectMocks`, and `when(...).thenReturn(...)` setup.
- **Smart Assertions**: Derives `assertEquals`, `assertTrue`, `assertNull` etc. from observed return values and side effects (logging, `System.out`).
- **Constructor Tests**: Optionally generates tests for constructors that have observable side effects.
- **Void Method Support**: Detects and asserts on side effects even when there is no return value.

### API / Spring MVC Test Generation
Generates RESTAssured or `MockMvc` tests for `@RestController` classes:

- Maps path variables and query parameters into typed test arguments.
- Generates one test per HTTP method + path combination.
- Handles `@PathVariable`, `@RequestParam`, and `@RequestBody` parameters.

### TestNG Support
All generated tests can target TestNG instead of JUnit 5 via configuration.

---

## How It Works

The generation pipeline follows three stages:

```
Spring Source Code
      │
      ▼
[ServicesParser / RestControllerParser]   ← parse & pre-process all classes
      │
      ▼
[SpringEvaluator + Branching]             ← symbolically execute each method,
      │                                     recording branch conditions
      ▼
[UnitTestGenerator / SpringTestGenerator] ← emit JUnit/TestNG test methods
      │                                     with mocks, preconditions, assertions
      ▼
  Generated Test Files
```

See [docs/unit-test-generation-sequence.md](docs/unit-test-generation-sequence.md)
for a detailed Mermaid sequence diagram of the full unit-test flow.

---

## Module Structure

```
antikythera-test-generator/
├── src/main/java/.../
│   ├── generator/
│   │   ├── Antikythera.java          — CLI entry point; orchestrates full generation run
│   │   ├── Factory.java              — creates the right TestGenerator for "unit" / "api" / "integration"
│   │   ├── TestGenerator.java        — abstract base; name deduplication, duplicate removal, save()
│   │   ├── UnitTestGenerator.java    — generates JUnit service-level tests
│   │   ├── SpringTestGenerator.java  — generates RESTAssured / MockMvc tests for controllers
│   │   ├── Asserter.java             — abstract assertion strategy
│   │   ├── JunitAsserter.java        — JUnit 5 assertion implementation
│   │   ├── TestNgAsserter.java       — TestNG assertion implementation
│   │   └── ControllerRequest.java    — models an HTTP request being generated
│   └── parser/
│       ├── ServicesParser.java       — entry point for service-class test generation
│       └── RestControllerParser.java — entry point for REST controller test generation
└── src/test/java/...                 — ~158 tests covering generators, parsers, asserters
```

---

## Getting Started

### Requirements

- Java 21
- Maven
- The `antikythera` core library installed locally (`mvn install` in `../antikythera/`)
- VM arguments: `-XX:+EnableDynamicAgentLoading`, `--add-opens java.base/java.nio.charset=ALL-UNNAMED`, `--add-opens java.base/java.lang=ALL-UNNAMED`, `--add-opens java.base/java.util.stream=ALL-UNNAMED`

### Build

```bash
# First, install the core library
cd ../antikythera
mvn install -Dmaven.test.skip=true

# Then build and test the generator
cd ../antikythera-test-generator
mvn test
```

### Running the Generator

Point it at your project via a `generator.yml` config file, then run:

```bash
mvn exec:java
```

Or directly:

```bash
mvn exec:java -Dexec.mainClass="sa.com.cloudsolutions.antikythera.generator.Antikythera"
```

---

## Configuration (`generator.yml`)

```yaml
base_package: com.example                 # Base package of the project under test
base_path: /path/to/project/src/main/java

# output_path points to the src/test/java directory.
# For unit tests (services only): this is the existing project's src/test/java.
# For API tests (controllers): this is the src/test/java of a standalone generated
#   project; Antikythera derives the project root and places pom.xml one level up.
output_path: /path/to/project/src/test/java

# Which classes to process
controllers:
  - com.example.controller.UserController   # triggers API / RESTAssured test generation
services:
  - com.example.service.UserService         # triggers unit / Mockito test generation

# Test framework
test_framework: junit                     # junit | testng
mock_with: Mockito

# Optional: skip void methods with no side effects (default: true)
# See "Testing Void Methods via Side Effects" section for details
skip_void_no_side_effects: true

# Optional: LogAppender class for asserting SLF4J log output in void method tests
# See "Testing Void Methods via Side Effects" section for details
log_appender: com.example.test.LogAppender

# Optional: generate tests for constructors (default: false)
generate_constructor_tests: false

# Optional: base class every generated test extends.
# Applies to both unit tests and API tests.
# API tests default to the built-in TestHelper when this is not set.
base_test_class: com.example.BaseTest

# Optional: extra imports added to every generated test class
extra_imports:
  - com.example.util.TestConstants
```

See [docs/configurations.md](docs/configurations.md) for the full reference, and
[antikythera/docs/configurations.md](https://github.com/Cloud-Solutions-International/antikythera/blob/main/docs/configurations.md) for the core settings
that also apply here.

---

## Base Test Class

When `base_test_class` is set, every generated test class — both unit tests (from `services`) and
API tests (from `controllers`) — will extend that class.

For **unit tests**, the parent class must already exist in your project's `src/test/java` tree.
Antikythera reads and analyses it at generation time so that it can integrate with whatever setup
the parent class performs.

For **API tests**, if `base_test_class` is not set the generated class falls back to extending the
built-in `TestHelper` class that is copied into the standalone test project.

### Why Use It

A shared base class lets you factor out boilerplate that would otherwise be duplicated across every
generated test file:

- Loading the Spring application context or configuring Mockito globally
- Providing reusable `@BeforeEach` / `@AfterEach` lifecycle hooks
- Declaring common mocks (e.g. a shared `@MockBean`) so the generator does not re-declare them in
  each subclass
- Supplying helper methods or inner classes used by assertions (e.g. a custom Mockito `Answer`)

### Configuration

```yaml
base_test_class: com.example.test.BaseTest
```

The value must be the fully qualified class name.  Antikythera derives the source file path from it
by converting the package/class to a relative path and resolving it under
`src/test/java` within your project root.

### What Antikythera Does With It

When the base class is configured, `UnitTestGenerator` performs these steps before emitting any
test code:

1. **Parse** the base class source file with JavaParser.
2. **Discover mocks** — any field annotated `@Mock` or `@MockBean` in the parent is registered.
   These are treated as already-declared and will not be re-emitted in the generated subclass.
3. **Execute `setUpBase()`** — if the parent class contains a method named `setUpBase()`, it is
   symbolically executed using `TestSuiteEvaluator`.  Any side effects (fields set, mocks
   registered) are captured and influence the test that is being generated.
4. **Add `extends`** — the generated test class declaration is updated to extend the configured
   parent.
5. **Emit a `setUpBase()` call** — the generated `@BeforeEach` setUp method will include a call to
   `setUpBase()` so the parent's runtime initialisation runs before each test.

### The `setUpBase()` Hook

`setUpBase()` is the primary integration point between the parent class and the generator.  Define
it in your base class to perform any per-test initialisation that every generated test needs:

Your base class defines `setUpBase()`:

```java
// YOUR hand-written base class
public class BaseTest {

    protected void setUpBase() {
        TenantContext.setTenantId("test-tenant");
        SecurityContext.setCurrentUser("test-user");
    }
}
```

Antikythera emits a call to it inside the `@BeforeEach` of every generated test:

```java
// GENERATED subclass
public class UserServiceAKTest extends BaseTest {

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        setUpBase();   // ← inserted by Antikythera
    }

    // ... generated test methods
}
```

The method must be `public` or `protected`, take no parameters, and return `void`.  The generator
will not call it if it does not exist.

### Declaring Shared Mocks

Any mock declared in the parent class is excluded from the generated subclass:

```java
public class BaseTest {

    @MockBean
    protected UserRepository userRepository;   // ← Antikythera sees this

    @MockBean
    protected AuditService auditService;       // ← and this
}
```

`userRepository` and `auditService` will be omitted from the `@Mock` / `@MockBean` declarations in
every generated test, preventing duplicate-bean errors when the test context starts.

### Example Parent Class

```java
package com.example.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.example.security.SecurityContext;

public class BaseTest {

    @MockBean
    protected SecurityContext securityContext;   // shared across all tests

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        setUpBase();                             // inserted by Antikythera
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    protected void setUpBase() {
        securityContext.setCurrentUser("test-user");
    }
}
```

With `base_test_class: com.example.test.BaseTest` in `generator.yml`, every generated test class
will:

- extend `BaseTest`
- omit a `@MockBean SecurityContext` field (already declared in the parent)
- include `setUpBase()` in its `@BeforeEach` method

---

## Testing Void Methods via Side Effects

Methods that return `void` cannot be tested through their return value alone. Antikythera detects
observable side effects produced during symbolic execution and emits assertions for them instead.
Two distinct mechanisms are supported: **stdout capture** and **SLF4J log capture**.

### Side Effect Detection

After each symbolic execution of a void method, Antikythera checks for the presence of any of the
following:

| Side Effect | How Detected |
| :--- | :--- |
| `System.out` / `System.err` output | Stdout is redirected during execution; non-empty result triggers a test |
| SLF4J log statements | A custom `AKLogger` routes all `logger.info/debug/warn/error` calls to `LogRecorder` |
| Mockito interactions (`when/then`) | Mocked dependency calls recorded in `GeneratorState` |
| Branching conditions reached | Presence of applicable conditions in `Branching` |
| Exceptions thrown | `Evaluator.getLastException()` is non-null |

If none of these is present and `skip_void_no_side_effects: true` (the default), no test is
generated for that execution path. Set `skip_void_no_side_effects: false` to generate tests for
all void methods regardless.

### Stdout Assertions

If the method writes to `System.out` or `System.err` during execution, the captured text is stored
in the `MethodResponse` and the generator emits an `assertEquals` against it:

```java
// Service method under test
public void printSummary(Order order) {
    System.out.println("Order total: " + order.getTotal());
}

// Generated test
@Test
void testPrintSummary() {
    // ... mock setup ...
    printSummaryAKTest();
    assertEquals("Order total: 0", outputStream.toString().trim());
}
```

The generated test class declares a `ByteArrayOutputStream outputStream` field and a `@BeforeEach`
that redirects `System.out` to it, so no manual setup is needed.

### SLF4J Log Assertions

For methods that use a logger rather than `System.out`, Antikythera generates assertions against a
Logback `AppenderBase` that you provide. This requires two steps:

#### 1. Supply a `LogAppender` class

Create a class in your `src/test/java` tree that extends Logback's `AppenderBase<ILoggingEvent>`:

```java
package com.example.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.List;

public class LogAppender extends AppenderBase<ILoggingEvent> {

    public static List<ILoggingEvent> events = new ArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
        events.add(event);
    }

    public static boolean hasMessage(Level level, String loggerName, String content) {
        return events.stream().anyMatch(e ->
                (level == null || e.getLevel() == level) &&
                (loggerName == null || e.getLoggerName().equals(loggerName)) &&
                (content == null || e.getFormattedMessage().contains(content)));
    }
}
```

A reference implementation is included in
[antikythera-sample-project](https://github.com/Cloud-Solutions-International/antikythera-sample-project)
at `src/test/java/sa/com/cloudsolutions/LogAppender.java`.

#### 2. Configure `log_appender` in `generator.yml`

```yaml
log_appender: com.example.test.LogAppender
```

Without this setting, SLF4J log assertions are skipped even when log output is observed.

#### What Gets Generated

When log output is detected, Antikythera generates a `setupLoggers()` `@BeforeEach` method in the
test class (only once, shared by all test methods in the file):

```java
@BeforeEach
/* Antikythera */
void setupLoggers() {
    appLogger = (Logger) LoggerFactory.getLogger(com.example.OrderService.class);
    appLogger.setAdditive(false);
    logAppender = new LogAppender();
    logAppender.start();
    appLogger.addAppender(logAppender);
    appLogger.setLevel(Level.INFO);
    LogAppender.events.clear(); // Clear static list from previous tests
}
```

It also adds two private fields to the test class:

```java
private Logger appLogger;
private LogAppender logAppender;
```

For each execution path, up to **5 log assertions** are emitted per test method. If logs were
expected but none were observed, an emptiness assertion is emitted instead:

```java
// When log output was captured:
assertTrue(LogAppender.hasMessage(Level.INFO, "com.example.OrderService", "Order placed"));
assertTrue(LogAppender.hasMessage(Level.WARN, "com.example.OrderService", "Stock low"));

// When no log output was captured:
assertTrue(LogAppender.events.isEmpty());
```

### Complete Example

Given a service method:

```java
@Service
public class OrderService {
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);

    public void placeOrder(Order order) {
        logger.info("Order placed: {}", order.getId());
        if (order.getTotal() > 1000) {
            logger.warn("High-value order: {}", order.getId());
        }
    }
}
```

With `log_appender: com.example.test.LogAppender` configured, Antikythera generates two tests (one
per branch) with assertions like:

```java
@Test
void testPlaceOrder() {
    // ... mock setup ...
    orderService.placeOrder(order);
    assertTrue(LogAppender.hasMessage(Level.INFO, "com.example.OrderService", "Order placed: "));
    assertTrue(LogAppender.hasMessage(Level.WARN, "com.example.OrderService", "High-value order: "));
}
```

---

## Relationship to `antikythera` Core

`antikythera-test-generator` depends on `antikythera` for:

| Core Feature | Used By |
| :--- | :--- |
| `AbstractCompiler` / `AntikytheraRunTime` | Parsing and type resolution |
| `Evaluator` / `SpringEvaluator` | Symbolic execution and branch exploration |
| `GeneratorState` | Shared static state (mock imports, `when/then` chains) |
| `ITestGenerator` | Interface wired into `SpringEvaluator` without circular dependency |
| `MethodResponse`, `TruthTable`, `TypeWrapper` | Shared model types |

The core library deliberately has **no dependency** on this module — it communicates only through
the `ITestGenerator` interface and `GeneratorState`.

---

## Development & Testing

The test suite covers generators, asserters, parsers, and evaluator integration:

```
src/test/java/
├── evaluator/   TestFields, TestQueries, TestSpringEvaluator
├── finch/       TestFinch
├── generator/   UnitTestGeneratorTest, TestSpringGenerator, JunitAsserterTest,
│                FactoryTest, SideEffectTest, ProjectGeneratorTest, TestTruthTable,
│                BaseRepositoryQueryTest, TestTestGenerator
└── parser/      RestControllerParserTest, ServicesParserTest
```

Tests rely on:
- [antikythera-test-helper](https://github.com/Cloud-Solutions-International/antikythera-test-helper) — sample entities, services, controllers
- [antikythera-sample-project](https://github.com/Cloud-Solutions-International/antikythera-sample-project) — realistic Spring Boot project for integration tests

---

## License

See [LICENSE.txt](../antikythera/LICENSE.txt) for details.
