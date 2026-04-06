# Antikythera Test Generation Guide

Antikythera is a sophisticated test generation engine that automatically creates comprehensive unit tests for Java and Spring Boot applications. This guide explains how it works and how to get the most out of it.

## Overview

Antikythera analyzes your Java codebase using symbolic execution and generates JUnit 5 or TestNG tests with full branch coverage. Unlike basic code generators, it understands your application's runtime behavior, Spring context, and dependency relationships to produce tests that actually work.

## How Test Discovery Works

Antikythera can operate in two modes: **explicit configuration** and **automatic discovery**.

### Automatic Discovery Mode

When you don't specify explicit `services` or `controllers` lists in your configuration, Antikythera enters automatic discovery mode (also called "fallback mode"). It scans your codebase and intelligently determines what should be tested.

**What gets tested:**
- Classes annotated with `@Service`
- Classes annotated with `@Component` that contain business logic
- Custom repository implementations with actual query logic
- Utility classes with executable methods

**What gets automatically skipped:**
- REST controllers and API endpoints
- JPA entities and data models
- DTOs, request/response objects, and other data carriers
- Configuration classes
- Interfaces, enums, records, and abstract classes
- Classes containing only constants

The discovery engine examines each class's structure, annotations, and method signatures to make intelligent decisions about testability.

### Explicit Configuration Mode

You can also explicitly specify which classes to test:

```yaml
services:
  - com.example.service.UserService
  - com.example.service.OrderService
  
controllers:
  - com.example.controller.RestController
```

This gives you precise control when needed, though most projects benefit from automatic discovery.

### Hybrid Approach

You can combine both modes by explicitly configuring critical classes while using discovery for the rest:

```yaml
services:
  - com.example.critical.PaymentService  # Always test this

# Skip the critical package from discovery
skip:
  - com.example.critical

# Let automatic discovery handle everything else
```

## Test Generation Capabilities

### Branch Coverage

Antikythera uses symbolic execution to explore all possible code paths through a method. For each branch (if/else, switch cases, try/catch), it generates a dedicated test case that exercises that specific path.

The symbolic execution engine:
- Tracks variable states through the code
- Handles complex control flow (nested loops, exception paths)
- Understands Spring context and dependency injection
- Executes code abstractly to understand behavior without running the application

This typically achieves 16+ branch coverage paths per method, ensuring comprehensive testing.

### Mock Generation

The test generator automatically identifies dependencies and creates appropriate mocks:

**Standard dependencies** get basic Mockito mocks with sensible return values configured via `when().thenReturn()` statements.

**Data access objects** (DAOs, Repositories) receive deep stub mocks. These support method chaining like `dao.findById(1).orElseGet(() -> new User())` without requiring extensive stubbing.

**HTTP clients and external services** also get deep stubs to handle fluent APIs and builder patterns common in these libraries.

**Spring `@Value` fields** are automatically injected with sensible defaults to prevent null pointer exceptions during test setup.

### Dependency Injection Handling

Antikythera handles Spring dependency injection intelligently:

When a service has straightforward dependencies, it uses Mockito's `@InjectMocks` for simple, clean setup.

When dependencies become complex (multiple fields of the same type, inherited fields, `@Value` properties), it switches to manual injection using Spring's `ReflectionTestUtils`. This ensures all fields are properly initialized regardless of constructor complexity.

For services that extend base classes, the generator examines the entire inheritance hierarchy to ensure all dependencies are properly mocked and injected.

### Exception Testing

The generator produces reliable exception tests through a two-phase approach:

First, symbolic execution identifies paths that appear to throw exceptions based on code analysis.

Then, during test code generation, the actual test execution is validated. If the test doesn't actually throw the expected exception at runtime (common with empty collections, null checks, or JSON serialization edge cases), the generator adjusts the assertion from `assertThrows` to `assertDoesNotThrow`.

This prevents the common problem of generated tests that fail because they expect exceptions that don't actually occur.

### Type Handling and Default Values

The generator understands Java's type system and generates appropriate default values:

**Numeric types** use proper literals (`0L` for Long, `0.0` for Double) to avoid type mismatch errors.

**String values** in numeric contexts use parseable strings like `"0"` instead of text that would cause `NumberFormatException`.

**Collections** are created as mutable `ArrayList`/`HashSet` instances rather than immutable `List.of()` constructs that would fail if the tested code tries to modify them.

**Constructor arguments** receive sensible defaults based on parameter types rather than all-null values that cause immediate failures.

**Boolean fields** follow JavaBeans naming conventions correctly, using `isActive()` for primitive booleans and `getIsActive()` for boxed Booleans.

### Logging and Side Effect Assertions

For methods that don't return values, Antikythera generates assertions on observable side effects:

**Log statements** are captured and verified using log appender mocks. The generator captures DEBUG-level and above, allowing comprehensive verification of business logic logging.

**System.out writes** are captured and asserted when methods write to standard output.

**Method invocations** on dependencies are verified using Mockito's `verify()` to ensure the tested code interacts with collaborators correctly.

## Base Test Class Integration

If your project has established test infrastructure with shared setup logic, Antikythera integrates seamlessly:

```yaml
base_test_class: com.example.BaseTest
```

Generated tests will extend your base class and reuse fields declared there. Instead of creating new instances of common test data objects, the generator references inherited fields:

```java
public class UserServiceTest extends BaseTest {
    @Test
    void testCreateUser() {
        Tenant tenant = this.tenant;  // Inherited from BaseTest
        // Test code uses shared fixture
    }
}
```

This reduces duplication and ensures generated tests follow your existing patterns.

## Configuration

### Minimal Setup

The simplest working configuration:

```yaml
base_package: com.example
base_path: /path/to/src/main/java
output_path: /path/to/src/test/java
test_framework: junit
```

Antikythera will discover all testable classes in the `com.example` package and generate JUnit 5 tests.

### Fine-Tuning Discovery

Control what gets tested with inclusion and exclusion rules:

```yaml
base_package: com.example

# Skip specific packages or patterns
skip:
  - com.example.legacy
  - com.example.Generated*
  - com.example.experimental

# Force inclusion despite automatic filtering
include:
  - com.example.dao.impl.CustomQueryExecutor
```

### Framework Selection

Choose between JUnit 5 and TestNG:

```yaml
test_framework: junit   # JUnit 5 (default)
# or
test_framework: testng  # TestNG
```

All generated tests adapt to the selected framework, including assertions, annotations, and lifecycle methods.

### Database Configuration

For repositories with custom queries, Antikythera can test them against a real database:

```yaml
database:
  url: jdbc:postgresql://localhost:5432/testdb
  user: test_user
  password: test_password
  schema: public
  run_queries: true
```

This validates that JPA queries work correctly with actual database interactions.

## Understanding the Generated Tests

### Test Structure

Each generated test follows a consistent structure:

**Setup** (`@BeforeEach`) - Initializes mocks and injects dependencies
**Test methods** - One per branch, with descriptive names indicating the path tested
**Assertions** - Verifies return values, exceptions, or side effects
**Mock configuration** - `when().thenReturn()` statements providing test data

### Test Naming

Tests are named to indicate the scenario being tested:

```java
testCreateUser_whenValidInput_returnsUser()
testCreateUser_whenNullTenant_throwsNullPointerException()
testDeleteUser_whenUserNotFound_logsWarning()
```

The naming pattern helps you understand coverage at a glance.

### Assertion Strategy

Return value assertions check that the method produces expected output.

Exception assertions verify error handling paths using `assertThrows`.

Logging assertions confirm that important events are logged correctly.

Verification calls ensure dependencies are invoked with correct parameters.

## Validation and Quality

Generated tests are designed to compile and run successfully. However, you should:

**Review the tests** to ensure they match your domain logic expectations.

**Run the test suite** to verify all tests pass. Any failures usually indicate edge cases in your code worth investigating.

**Check coverage reports** to confirm that generated tests achieve the expected branch coverage.

**Adjust configuration** if tests are generated for classes that shouldn't be tested, or if important classes are skipped.

## Common Patterns

### Testing Services with Multiple DAOs

When a service injects multiple data access objects of the same type, the generator uses reflective field injection:

```java
@BeforeEach
void setUp() {
    MockitoAnnotations.openMocks(this);
    patientService = new PatientService();
    ReflectionTestUtils.setField(patientService, "primaryDao", primaryDao);
    ReflectionTestUtils.setField(patientService, "archiveDao", archiveDao);
}
```

This handles ambiguous injection scenarios that `@InjectMocks` cannot.

### Testing Controllers with Path Variables

REST controller tests capture `@PathVariable` and `@RequestParam` values:

```java
@Test
void testGetUser() {
    Long userId = 1L;
    when(userService.findById(userId)).thenReturn(testUser);
    // Test invokes endpoint
}
```

The generator understands Spring MVC annotations and creates appropriate test data.

### Testing Methods with Loops

For methods containing loops that might throw exceptions:

```java
for (Item item : items) {
    if (item.isInvalid()) {
        throw new IllegalArgumentException();
    }
}
```

The generator creates test cases with both empty collections (no exception) and collections with invalid items (exception thrown), ensuring comprehensive coverage.

## Best Practices

**Start with automatic discovery** to understand what your codebase contains, then refine with skip rules.

**Use explicit configuration** for critical business logic where you need guaranteed test coverage.

**Leverage base test classes** if you have shared setup logic across your test suite.

**Review discovery logs** to understand why specific classes were included or excluded.

**Iterate incrementally** - generate tests for one package, review quality, adjust configuration, expand scope.

**Validate with real runs** - generated tests are a starting point; manual review catches domain-specific edge cases.

## Troubleshooting

### Tests Don't Compile

Usually caused by missing dependencies in the classpath. Check that:
- All required JAR files are in the Maven repository
- The `dependencies.on_error` setting is appropriate for your environment
- Type resolution logs show successful class loading

### Tests Fail on Execution

Common causes:
- **Exception assertions mismatch** - Check if the code path actually throws what's asserted
- **Mock return values** - Verify stubs return appropriate types for your domain
- **Initialization order** - Ensure `@BeforeEach` setup completes before tests run

### Classes Not Discovered

Check the INFO logs to see why classes were filtered:
- Is the class an entity, DTO, or config class?
- Does it have any executable methods?
- Is it in a skipped package?

Use the `include` configuration to force generation if needed.

## Advanced Topics

### Custom Repository Testing

For repositories with hand-written queries, Antikythera can generate both unit tests (mocked) and integration tests (real database). Configure database connection details to enable query validation.

### Spring Context Awareness

The symbolic execution engine understands Spring annotations and bean lifecycle:
- `@Autowired` dependencies are mocked appropriately
- `@PostConstruct` methods are considered in test setup
- `@Transactional` behavior is accounted for in assertion generation

### Multi-Module Projects

For Maven multi-module projects, configure the base path to point to individual modules and generate tests module-by-module. Each module's tests can have independent configuration.

---

This guide covers the core concepts and capabilities of Antikythera's test generation engine. For detailed configuration options, see the configuration documentation. For implementation details, consult the AGENTS.md file in the repository.
