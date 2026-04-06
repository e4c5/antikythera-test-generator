# Antikythera Test Generator — Agent Guide

Guidance for AI coding agents working on **antikythera-test-generator**.

This module generates JUnit 5 / TestNG unit tests, Mockito-backed service tests, and RESTAssured
API tests for Java/Spring applications. It depends on the `antikythera` core library and must be
built after it.

---

## Build & Test Commands

```bash
# First, install the core library if not already done
cd ../antikythera
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto mvn install -Dmaven.test.skip=true

# Compile this module
cd ../antikythera-test-generator
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto mvn compile

# Run all tests
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto mvn test

# Single test class
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto mvn test -Dtest=UnitTestGeneratorTest

# Single test method
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto mvn test -Dtest=UnitTestGeneratorTest#testBasicMethod

# Run the generator CLI
JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto mvn exec:java
```

> Always prefix `mvn` with `JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto`.
> The system `/usr/bin/mvn` uses Java 8 and will fail on text blocks and other Java 21 features.

---

## ⚠️ Critical Rules

1. **Install antikythera core first.** This module depends on `antikythera` at compile time.
   If core classes have changed, run `mvn install -Dmaven.test.skip=true` in `../antikythera`
   before building or testing here.

2. **Do not duplicate `GeneratorState` or `ITestGenerator`.** These live in the core
   (`antikythera/evaluator/`) and are the defined interface between the two modules. All shared
   static state (mock imports, when/then chains) belongs there — do not hold it locally in
   `TestGenerator` or anywhere else in this module.

3. **Do not access `TestGenerator` static fields directly.** The fields `whenThen` and `imports`
   no longer exist on `TestGenerator`. Always use the static delegate methods:
   `TestGenerator.addWhenThen()`, `TestGenerator.getWhenThen()`, `TestGenerator.clearWhenThen()`,
   `TestGenerator.addImport()`, `TestGenerator.getImports()`.

4. **Do not copy shared model types into this module.** `TypeWrapper`, `MethodResponse`, `TruthTable`,
   `RepositoryQuery*`, and `CopyUtils` live in `antikythera/generator/` and are available as a
   transitive dependency. Copies here will create divergence.

5. **`Factory` caches generators per class.** One `TestGenerator` instance is created per
   compilation unit and reused across all method evaluations on that class. Avoid resetting
   generator-level state (e.g., `testMethodNames`) between methods.

6. **Tests run sequentially.** The `pom.xml` sets `<parallel>none</parallel>` and
   `<runOrder>alphabetical</runOrder>`. Do not change this — evaluators use static state that
   is not thread-safe.

---

## Architecture

```
generator/
  Antikythera.java          — CLI singleton; reads generator.yml, calls generateUnitTests() / generateApiTests()
  Factory.java              — creates and caches TestGenerator instances ("unit" | "api" | "integration")
  TestGenerator.java        — abstract base; name deduplication, duplicate test removal, save()
  UnitTestGenerator.java    — generates Mockito-backed JUnit/TestNG tests for service classes
  SpringTestGenerator.java  — generates RESTAssured / MockMvc tests for @RestController classes
  Asserter.java             — abstract assertion strategy
  JunitAsserter.java        — JUnit 5 assertion emitter (assertEquals, assertThrows, etc.)
  TestNgAsserter.java       — TestNG assertion emitter
  ControllerRequest.java    — models one HTTP request being generated (method, path, params, body)

parser/
  ServicesParser.java       — entry point for service class test generation; drives branch-coverage loop
  RestControllerParser.java — entry point for REST controller test generation; maps endpoints to requests
```

### Generation Pipeline (Unit Tests)

```
ServicesParser.evaluateMethod(md, argumentGenerator)
  ├─ Factory.create("unit", cu)          → UnitTestGenerator (cached per class)
  ├─ EvaluatorFactory.create(fqn)        → SpringEvaluator (from antikythera core)
  ├─ evaluator.addGenerator(gen)
  └─ evaluator.visit(md)                 ← branch-coverage loop (up to 16 iterations)
        for each branch:
          evaluator.executeMethod(md)
            └─ on each return/exit: gen.createTests(md, response) is called
                 UnitTestGenerator.buildTestMethod()
                 → mockArguments()
                 → applyPreconditions()
                 → addWhens()            ← reads GeneratorState.getWhenThen()
                 → invokeMethod()
                 → addAsserts(response)
          GeneratorState.clearWhenThen() ← called by SpringEvaluator after each iteration
  └─ generator.save()                    → removeDuplicateTests(), write .java file
```

---

## Key Integration Points with antikythera Core

| Core Class | Used By | Why |
| :--- | :--- | :--- |
| `ITestGenerator` | `TestGenerator implements ITestGenerator` | Contract for `SpringEvaluator.addGenerator()` |
| `GeneratorState` | `TestGenerator` static delegates | Holds imports/when-then written by `MockingEvaluator` |
| `MethodResponse` | `UnitTestGenerator.createTests(md, response)` | Carries return value or exception from evaluation |
| `ArgumentGenerator` | `UnitTestGenerator` | Generates mock argument values |
| `Precondition` | `UnitTestGenerator.applyPreconditions()` | Sets up field state to reach a branch |
| `SpringEvaluator` | `ServicesParser`, `RestControllerParser` | Created via `EvaluatorFactory.create()` |

---

## Common Patterns

### Generate unit tests for all configured services
```java
Settings.loadConfigMap(new File("generator.yml"));
AbstractCompiler.preProcess();
Antikythera.getInstance().generateUnitTests();
```

### Create a generator for a specific compilation unit
```java
TestGenerator gen = Factory.create("unit", compilationUnit);
gen.setAsserter(new JunitAsserter());
```

### Add field-level assertions after evaluation
```java
JunitAsserter asserter = new JunitAsserter();
MethodResponse mr = new MethodResponse();
mr.setBody(new Variable(evaluator));   // evaluator has run the method
BlockStmt block = new BlockStmt();
asserter.addFieldAsserts(mr, block);
// block now contains assertEquals calls for each accessible getter
```

### Plug in a custom asserter
```java
gen.setAsserter(new MyCustomAsserter());
// MyCustomAsserter must extend Asserter
```

---

## State Lifecycle

| State | Location | When to clear |
| :--- | :--- | :--- |
| `when/then` chains | `GeneratorState.whenThen` | After each `executeMethod()` call — `GeneratorState.clearWhenThen()` |
| Extra imports | `GeneratorState.imports` | Between output files — `GeneratorState.clearImports()` |
| Test method names | `TestGenerator.testMethodNames` | Per generator instance (one per class); do not clear manually |
| Branch conditions | `Branching` (core) | Before each method — `Branching.clear()` called by `ServicesParser` |

---

## Test Setup

Tests in this module share the same external test repositories as antikythera core:

- `antikythera-test-helper` — sample entities, services, controllers used as test fixtures
- `antikythera-sample-project` — realistic Spring Boot project for integration tests

Both must be cloned at the same folder level as this project.

VM arguments required (already set in `pom.xml` via `argLine`):
```
-javaagent:${antikythera.agent.path}
-XX:+EnableDynamicAgentLoading
--add-opens java.base/java.nio.charset=ALL-UNNAMED
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/java.util.stream=ALL-UNNAMED
```

---

## Key Files

| File | Purpose |
| :--- | :--- |
| `generator/Antikythera.java` | CLI entry point; start here to trace the full generation flow |
| `generator/Factory.java` | Generator creation and caching per class |
| `generator/TestGenerator.java` | Abstract base; `buildTestMethod()`, `removeDuplicateTests()`, `save()` |
| `generator/UnitTestGenerator.java` | Core unit test emitter; `createTests()` is the main method |
| `generator/SpringTestGenerator.java` | REST API test emitter |
| `generator/JunitAsserter.java` | JUnit 5 assertions; `addFieldAsserts()` for POJO/Lombok fields |
| `parser/ServicesParser.java` | Drives the branch-coverage evaluation loop for services |
| `parser/RestControllerParser.java` | Drives test generation for controllers |
