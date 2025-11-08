# Almost Realism LLVM Module (`ar-llvm`)

The LLVM Module provides integration with LLVM (Low-Level Virtual Machine) and native C code execution through GraalVM's polyglot capabilities. It enables Almost Realism to execute compiled LLVM IR and C code for performance-critical operations.

## Purpose

This module exists to:

1. **Enable LLVM Integration** - Execute LLVM IR from Java
2. **Support C Interoperability** - Call C functions from Almost Realism
3. **Provide Polyglot Capabilities** - Multi-language integration via GraalVM
4. **Optimize Performance** - Native code execution for critical paths
5. **Bridge to Hardware** - Integration with ar-hardware for acceleration

## What It Provides

### 1. GraalVM Polyglot Context

```java
import org.graalvm.polyglot.*;

// Create polyglot context with full access
Context polyglot = Context.newBuilder()
    .allowAllAccess(true)
    .build();

// Load and execute LLVM IR
File llvmFile = new File("compiled_code.ll");
Source source = Source.newBuilder("llvm", llvmFile).build();
Value result = polyglot.eval(source);

// Execute function
Value cFunction = result.getMember("myFunction");
int output = cFunction.execute(42).asInt();
```

### 2. C Code Integration

```java
// Load compiled C code
File cBitcode = new File("native_lib.bc");  // LLVM bitcode
Source source = Source.newBuilder("llvm", cBitcode).build();
Value lib = polyglot.eval(source);

// Call C function
Value addFunction = lib.getMember("add");
int result = addFunction.execute(10, 20).asInt();
```

### 3. Java-C Interop

```c
// C code with GraalVM polyglot support
#include <graalvm/llvm/polyglot.h>

// Access Java arrays from C
void processArray() {
    void *arrayType = polyglot_java_type("int[]");
    void *array = polyglot_new_instance(arrayType, 4);

    // Set array elements
    polyglot_set_array_element(array, 0, 10);
    polyglot_set_array_element(array, 1, 20);
    polyglot_set_array_element(array, 2, 30);
    polyglot_set_array_element(array, 3, 40);

    // Get array elements
    int value = polyglot_as_i32(polyglot_get_array_element(array, 2));
}

// Export function to Java
void *my_c_function(int x, int y) __attribute__((export_name("my_c_function")));

void *my_c_function(int x, int y) {
    return (void *)(long)(x + y);
}
```

## Key Components

### GraalVM Context

```java
Context polyglot = Context.newBuilder()
    .allowAllAccess(true)       // Allow access to Java objects
    .allowIO(true)              // Allow file I/O
    .allowNativeAccess(true)    // Allow native code execution
    .build();
```

### Source Loading

```java
// From file
Source llvmSource = Source.newBuilder("llvm", new File("code.ll")).build();

// From string
String llvmCode = "; LLVM IR code\ndefine i32 @add(i32 %a, i32 %b) { ... }";
Source source = Source.newBuilder("llvm", llvmCode, "inline.ll").build();

// From URL
URL llvmUrl = new URL("http://example.com/code.bc");
Source source = Source.newBuilder("llvm", llvmUrl).build();
```

### Value Handling

```java
Value result = polyglot.eval(source);

// Function calls
Value function = result.getMember("functionName");
Value output = function.execute(arg1, arg2, arg3);

// Type conversions
int intResult = output.asInt();
long longResult = output.asLong();
double doubleResult = output.asDouble();
String stringResult = output.asString();

// Array access
Value array = result.getMember("myArray");
int element = array.getArrayElement(0).asInt();
```

## Common Patterns

### Pattern 1: Calling C Functions

```java
// Compile C to LLVM bitcode first:
// clang -c -emit-llvm mycode.c -o mycode.bc

// In Java:
Context ctx = Context.newBuilder().allowAllAccess(true).build();
Source source = Source.newBuilder("llvm", new File("mycode.bc")).build();
Value lib = ctx.eval(source);

// Call function
Value multiply = lib.getMember("multiply");
int result = multiply.execute(6, 7).asInt();  // 42

System.out.println("Result: " + result);
```

### Pattern 2: Performance-Critical Operations

```c
// optimized_math.c
__attribute__((export_name("fast_dot_product")))
double fast_dot_product(double *a, double *b, int n) {
    double sum = 0.0;
    for (int i = 0; i < n; i++) {
        sum += a[i] * b[i];
    }
    return sum;
}
```

```java
// In Java (compile C to LLVM first)
Context ctx = Context.newBuilder().allowAllAccess(true).build();
Value lib = ctx.eval(Source.newBuilder("llvm", new File("optimized_math.bc")).build());

// Call optimized function
Value dotProduct = lib.getMember("fast_dot_product");
double result = dotProduct.execute(arrayA, arrayB, size).asDouble();
```

### Pattern 3: Integrating with Hardware Module

```java
import org.almostrealism.hardware.HardwareOperator;

// Use LLVM for specific kernels
Context llvmContext = Context.newBuilder().allowAllAccess(true).build();
Source kernel = Source.newBuilder("llvm", new File("kernel.bc")).build();
Value compiledKernel = llvmContext.eval(kernel);

// Integrate with hardware operator
HardwareOperator.setCustomKernel("matmul", (args) -> {
    return compiledKernel.getMember("optimized_matmul").execute(args);
});
```

## Compiling C to LLVM

### Using Clang

```bash
# Compile C to LLVM bitcode (.bc)
clang -c -emit-llvm mycode.c -o mycode.bc

# Compile C to LLVM IR text (.ll)
clang -S -emit-llvm mycode.c -o mycode.ll

# With optimizations
clang -O3 -c -emit-llvm mycode.c -o mycode.bc

# Multiple files
clang -c -emit-llvm file1.c file2.c -o combined.bc
```

### Example C File

```c
// example.c
#include <stdint.h>

// Simple addition
int32_t add(int32_t a, int32_t b) {
    return a + b;
}

// Matrix multiplication
void matmul(float *A, float *B, float *C, int N) {
    for (int i = 0; i < N; i++) {
        for (int j = 0; j < N; j++) {
            float sum = 0.0f;
            for (int k = 0; k < N; k++) {
                sum += A[i * N + k] * B[k * N + j];
            }
            C[i * N + j] = sum;
        }
    }
}
```

Compile:
```bash
clang -O3 -c -emit-llvm example.c -o example.bc
```

## Integration with Other Modules

### Hardware Module
- **Native code execution** for performance-critical kernels
- **Custom operators** implemented in C/LLVM
- **GPU interfacing** through native libraries

### Code Module
- **Code generation** target for compiled operations
- **JIT compilation** alternative to Java bytecode
- **Low-level optimization** opportunities

## Performance Considerations

### When to Use LLVM

✅ **Use LLVM for:**
- Tight numerical loops (matrix operations, signal processing)
- Performance-critical kernels
- Legacy C/C++ code integration
- Platform-specific optimizations

❌ **Don't use LLVM for:**
- Simple operations (overhead exceeds benefit)
- Code that changes frequently (compilation overhead)
- Operations already optimized in ar-hardware

### Benchmarking

```java
// Measure performance difference
long startJava = System.nanoTime();
double resultJava = javaImplementation(data);
long javaTime = System.nanoTime() - startJava;

long startLLVM = System.nanoTime();
double resultLLVM = llvmFunction.execute(data).asDouble();
long llvmTime = System.nanoTime() - startLLVM;

System.out.println("Java: " + javaTime + " ns");
System.out.println("LLVM: " + llvmTime + " ns");
System.out.println("Speedup: " + ((double)javaTime / llvmTime) + "x");
```

## Requirements

### GraalVM Installation

```bash
# Download GraalVM
# Install with LLVM support
gu install llvm-toolchain

# Verify
lli --version
```

### Environment Variables

```bash
export JAVA_HOME=/path/to/graalvm
export PATH=$JAVA_HOME/bin:$PATH
```

## Dependencies

```xml
<dependency>
    <groupId>org.graalvm.polyglot</groupId>
    <artifactId>polyglot</artifactId>
    <version>23.1.0</version>
</dependency>

<dependency>
    <groupId>org.graalvm.polyglot</groupId>
    <artifactId>llvm-community</artifactId>
    <version>23.1.0</version>
    <type>pom</type>
</dependency>

<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-hardware</artifactId>
    <version>0.72</version>
</dependency>
```

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-llvm</artifactId>
    <version>0.72</version>
</dependency>
```

## Further Reading

- [GraalVM Polyglot Documentation](https://www.graalvm.org/latest/reference-manual/polyglot-programming/)
- [LLVM Documentation](https://llvm.org/docs/)
- See **ar-hardware** module for hardware acceleration
- See **ar-code** module for code generation
