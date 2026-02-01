# Testing Guidelines - Detailed Examples

This document contains detailed testing examples and patterns.
See the main [CLAUDE.md](../../CLAUDE.md) for the rules.

## Test Class Structure

```java
// CORRECT: Extend TestSuiteBase
public class MyTest extends TestSuiteBase {
    @Test
    public void testSomething() {
        // Test automatically participates in grouping and depth filtering
    }

    @Test
    @TestDepth(2)
    public void expensiveTest() {
        // Automatically skipped if AR_TEST_DEPTH < 2
    }
}
```

```java
// WRONG: Implementing TestFeatures directly
public class MyTest implements TestFeatures {
    // This test will NOT participate in test grouping!
    // It will run in ALL CI groups, wasting resources
}
```

```java
// WRONG: Manually adding TestDepthRule
public class MyTest implements TestFeatures {
    @Rule public TestDepthRule depthRule = testDepthRule();  // NEVER DO THIS!
}
```

## Long-Running Tests

For tests taking 30+ minutes, use `skipLongTests` guard:

```java
public class MyTest extends TestSuiteBase {
    @Test
    @TestDepth(3)
    public void veryExpensiveTest() {
        if (skipLongTests) return;  // Respects AR_LONG_TESTS env var
        // ...
    }
}
```

## Test Output Logging

Use `Console` and `OutputFeatures` for file logging:

```java
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;

public class MyTest implements ConsoleFeatures {
    @Test
    public void myTest() throws Exception {
        String logFile = "/workspace/project/common/<module>/test_output/my_test_results.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("=== My Test ===");
        log("Result: " + someValue);
    }
}
```

## MCP Test Runner Reference

**Available tools:**
| Tool | Purpose |
|------|---------|
| `mcp__ar-test-runner__start_test_run` | Start a test run |
| `mcp__ar-test-runner__get_run_status` | Check test run status |
| `mcp__ar-test-runner__get_run_output` | Get console output |
| `mcp__ar-test-runner__get_run_failures` | Get detailed failure info |
| `mcp__ar-test-runner__list_runs` | List recent test runs |
| `mcp__ar-test-runner__cancel_run` | Cancel a running test |

**Parameters for `start_test_run`:**
- `module`: Maven module to test (e.g., "ml", "utils")
- `test_classes`: List of specific test classes
- `test_methods`: List of specific test methods
- `profile`: Test profile name (sets AR_TEST_PROFILE)
- `depth`: AR_TEST_DEPTH value (0-10)
- `jvm_args`: Additional JVM arguments
- `timeout_minutes`: Max run time

**Example:**
```
mcp__ar-test-runner__start_test_run
  module: "ml"
  profile: "pipeline"
  timeout_minutes: 10
```
