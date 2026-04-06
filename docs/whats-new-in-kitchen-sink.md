# What's New in Antikythera Test Generator

**Version:** Kitchen-Sink Release  
**Date:** April 4, 2026

Welcome to the latest release of Antikythera Test Generator! This update brings significant improvements to test quality, reliability, and ease of use.

---

## 🎯 What's Changed

This release adds over 4,000 lines of improvements across 31 files, focusing on making your generated tests more reliable and easier to maintain.

---

## ✨ New Features

### 1. Automatic Test Discovery

**What it does:** Automatically finds all classes that need tests without manual configuration.

**How it helps you:**
- No more manually listing every service in `generator.yml`
- Just set your `base_package` and let Antikythera discover test targets
- Smart filtering automatically skips classes that shouldn't be tested (controllers, entities, DTOs, etc.)

**How to use it:**

Simply remove the `services` and `controllers` lists from your `generator.yml`:

```yaml
# Old way - manual list
services:
  - com.example.UserService
  - com.example.OrderService
  - com.example.ProductService
  # ... 50+ more services

# New way - automatic discovery
base_package: com.example
# That's it! Antikythera finds everything for you
```

**What gets tested automatically:**
- `@Service` classes
- `@Component` classes with business logic
- Custom `@Repository` implementations (with real query logic)
- Utility classes with executable methods

**What gets skipped automatically:**
- Controllers and REST endpoints
- JPA Entities and data models
- DTOs, Requests, Responses (data carriers)
- Configuration classes
- Interfaces, enums, records, abstract classes
- Constant-only classes

**Fine-tune with overrides:**

```yaml
# Skip specific packages or classes
skip:
  - com.example.legacy
  - com.example.Generated*

# Force inclusion of specific classes
include:
  - com.example.dao.CustomRepositoryImpl
```

---

### 2. Smarter Exception Testing

**What it does:** Generates more reliable exception tests that actually work.

**The problem we solved:**  
Previously, generated tests would sometimes fail because they expected exceptions that never actually occurred at runtime. This happened because symbolic execution (code analysis) and actual test execution behave differently.

**How it helps you:**
- Fewer failing tests out of the box
- No more false `assertThrows` that fail when you run tests
- Better handling of null checks, loops, and JSON serialization

**Examples:**

**Loop-based exceptions:**
```java
// Your code
for (Item item : items) {
    if (item.isInvalid()) {
        throw new IllegalArgumentException();
    }
}

// Old behavior: Always generated assertThrows
// New behavior: Detects when empty collection = no exception
// If test passes empty list → generates assertDoesNotThrow instead
```

**NullPointerException detection:**
```java
// Detects when NPE comes from evaluator vs real runtime
// Only generates assertThrows(NPE) when test truly passes null
```

**JSON serialization:**
```java
// Gson/Jackson behavior varies with mocks
// Uses assertDoesNotThrow for serialization-related exceptions
```

---

### 3. Better Mock Injection

**What it does:** Fixes dependency injection issues in generated tests.

**The problem we solved:**  
The old `@InjectMocks` approach failed when a service had two fields of the same type (e.g., two different DAO fields).

**How it helps you:**
- Tests work correctly with duplicate field types
- More reliable setup for all Spring components
- Handles `@Value` fields automatically

**What changed:**

**Before:**
```java
@InjectMocks  // Fails with duplicate types!
private PatientService patientService;

@Mock
private PatientDao primaryDao;

@Mock
private PatientDao archiveDao;  // Ambiguous!
```

**After:**
```java
@BeforeEach
void setUp() {
    MockitoAnnotations.openMocks(this);
    patientService = new PatientService();
    ReflectionTestUtils.setField(patientService, "primaryDao", primaryDao);
    ReflectionTestUtils.setField(patientService, "archiveDao", archiveDao);
}
```

**Bonus:** `@Value` fields get sensible defaults automatically (preventing NPE from null values).

---

### 4. Base Test Class Integration

**What it does:** Reuses fields from your existing base test classes.

**How it helps you:**
- Better integration with existing test infrastructure
- No duplicate object creation
- Respects your testing patterns

**Example:**

```java
// Your base test class
public abstract class BaseTest {
    protected Tenant tenant;
    protected MetaData metaData;
}

// Generated test
@Test
void testCreatePatient() {
    Tenant tenant = this.tenant;  // Reuses base class field!
    // Instead of: Tenant tenant = new Tenant();
}
```

---

### 5. Improved Type Handling

**What it does:** Automatically fixes type mismatches in generated test data.

**How it helps you:**
- No more type mismatch compilation errors
- Better handling of numbers, strings, and collections
- Smarter default values

**Improvements:**

**Number coercion:**
```java
person.setId(0L);   // Correctly uses Long literal
// Not: person.setId(0);  (Integer would cause error)
```

**String placeholders:**
```java
setUserId("0");  // Parseable string for numeric contexts
// Not: setUserId("Antikythera");  (would break parseInt)
```

**Collections:**
```java
List<String> items = new ArrayList<>();  // Mutable
// Not: List.of()  (immutable, throws exception on .add())
```

**Constructor arguments:**
```java
new PatientDto("0", 0L);  // Sensible defaults
// Not: new PatientDto(null, null);  (causes NPE)
```

---

### 6. Smart Mock Strategies

**What it does:** Uses the right kind of mocks for different dependencies.

**How it helps you:**
- More realistic test behavior
- Less manual stubbing needed
- Better handling of method chains

**Strategy:**

| Your Dependency | Mock Type | Why |
|----------------|-----------|-----|
| `UserDao`, `OrderRepository` | Deep Stubs | Supports chained calls like `dao.find().get()` |
| `HttpClient`, `ApiClient` | Deep Stubs | HTTP client method chaining |
| `ProblemFeignClient` (and others) | Plain Mock when listed in `plain_mock_dependency_simple_names` | Preserves error handling behavior without hard-coding type names |
| Everything else | Plain Mock | Standard behavior |

---

### 7. Better Boolean Field Support

**What it does:** Correctly generates getter/setter names for boolean fields.

**How it helps you:**
- No more method-not-found errors
- Follows JavaBeans specification exactly
- Handles both primitive and boxed booleans

**Examples:**

```java
// Primitive boolean
private boolean isActive;
// Generated: isActive(), setIsActive()

// Boxed Boolean
private Boolean isActive;
// Generated: getIsActive(), setIsActive()
```

---

### 8. Enhanced Logging Assertions

**What it does:** Captures more log output for better test coverage.

**Change:** Log level changed from INFO to DEBUG

**How it helps you:**
- More comprehensive log assertion testing
- Catches debug-level business logic logs
- Better verification of application behavior

---

## 📚 Documentation Updates

We've significantly improved documentation:

- **New:** Full-project discovery guide
- **New:** Improvement roadmap and implementation tracking
- **Updated:** README with fallback mode usage
- **Updated:** Configuration guide with generation modes
- **New:** Validation script for external service testing

---

## 🚀 Getting Started

### Minimal Configuration

The simplest `generator.yml` you can have:

```yaml
base_package: com.example
base_path: /path/to/project/src/main/java
output_path: /path/to/project/src/test/java
test_framework: junit
```

That's it! Antikythera will:
- Discover all testable classes in `com.example`
- Skip infrastructure (controllers, entities, configs)
- Generate comprehensive unit tests
- Use smart mocks and type handling

### Migration from Previous Versions

**If you have an explicit `services` list:**
- No changes needed! Explicit configuration still works
- You can migrate gradually or keep manual configuration

**To enable automatic discovery:**
1. Remove or comment out `services:` and `controllers:` sections
2. Run the generator
3. Review the logs to see what was discovered
4. Add `skip:` entries for anything you want to exclude

### Advanced Configuration

```yaml
base_package: com.example
base_path: /path/to/project/src/main/java
output_path: /path/to/project/src/test/java

# Fine-tune discovery
skip:
  - com.example.legacy       # Skip entire package
  - com.example.Generated*   # Skip generated code
  
include:
  - com.example.dao.impl     # Force include

# Test framework
test_framework: junit  # or testng

# Optional base class
base_test_class: com.example.BaseTest
```

---

## 📊 Impact Summary

**Test Quality Improvements:**
- ✅ Fewer failing tests due to smart exception handling
- ✅ Better type safety and fewer compilation errors
- ✅ More realistic mocks with deep stubs
- ✅ Proper handling of duplicate dependencies

**Developer Experience:**
- ✅ Automatic test discovery (no manual configuration)
- ✅ Better integration with existing test infrastructure
- ✅ Comprehensive documentation
- ✅ Validation tools for quality assurance

**Code Coverage:**
- ✅ More comprehensive log assertions
- ✅ Better boolean field handling
- ✅ Improved edge case handling

---

## 🔧 Known Limitations

1. **Binary-Only Classes** - Classes without source code have limited classification support
2. **Complex Inheritance** - Very deep inheritance hierarchies may need manual setter configuration
3. **Enum Logic** - Enums with business logic are currently skipped (future enhancement planned)

---

## 💡 Tips & Best Practices

### 1. Start with Automatic Discovery
Let Antikythera discover your tests first, then refine with `skip` rules.

### 2. Review Discovery Logs
Check the INFO logs to understand what was selected:
```
INFO: Fallback discovery under base_package 'com.example': 
      234 type(s) in scope, 47 unit target(s)
```

### 3. Use Base Test Classes
If you have common test setup, use `base_test_class` for better integration.

### 4. Validate Generated Tests
Use the new validation script to check test quality:
```bash
./scripts/validate_external_services.py /path/to/generator.yml
```

### 5. Gradual Migration
You can use both explicit and automatic discovery:
```yaml
services:
  - com.example.critical.UserService  # Explicit
  
# Automatic discovery for everything else
skip:
  - com.example.critical  # Skip what's explicitly listed
```

---

## 🆘 Getting Help

**Documentation:**
- [README](../README.md) - Main documentation
- [Configuration Guide](configurations.md) - Detailed settings
- [Discovery Plan](full-project-discovery-plan.md) - Technical details

**Issues:**
If you encounter any problems, check:
1. Generated test compilation errors → Review type coercion logs
2. Failing exception tests → Check exception analysis logs
3. Missing tests → Review discovery INFO logs for skip reasons

---

## 🎉 Upgrade Today!

The kitchen-sink release represents the most significant improvement to test generation quality and developer experience. Whether you're starting fresh or migrating existing tests, the new automatic discovery and smart testing features will save you time and reduce maintenance burden.

**Happy Testing!**

---

*Last updated: April 4, 2026*
