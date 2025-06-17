# Algebra Module Agent Memory

## Computation Classes

# Build

```shell
mvn package -pl algebra -DskipTests
```

Do NOT attempt to run the build for this module by navigating to the
module's directory as doing this will prevent the dependent modules
from being loaded and respected.

Always run `mvn` commands from the root of the repository.

# Test

Tests are always located in the utils module, so you should always run tests from there.

```shell
LD_LIBRARY_PATH=Extensions mvn test \
  -pl utils \
  -Dtest=<test name> \
  -DAR_HARDWARE_DRIVER=native \
  -DAR_HARDWARE_MEMORY_SCALE=7 \
  -DAR_HARDWARE_LIBS=Extensions \
  -DAR_TEST_PROFILE=pipeline
```

Do NOT attempt to run the tests for a module by navigating to the
module's directory as doing this will prevent the dependent modules
from being loaded and respected.

Always run `mvn` commands from the root of the repository.

## Documentation Patterns
- Use comprehensive examples showing practical applications
- Include performance considerations and optimization notes
- Document parameter relationships and their effects on performance
- Use @link for class references in javadoc
- Include code examples that demonstrate realistic usage patterns from the codebase


## Key Components

### ConstantRepeatedProducerComputation
- Extends RepeatedProducerComputation with fixed iteration counts
- Used primarily for reduction operations (e.g., finding max index in CollectionFeatures)
- Enables compiler optimizations through constant iteration bounds
- Has specialized delta computation via ConstantRepeatedDeltaComputation
- Constructor with memory length parameter (size) controls elements per kernel thread

### CollectionExponentComputation
- Implements element-wise exponentiation (power operations) on multi-dimensional collections
- Extends TraversableExpressionComputation for parallel processing capabilities
- Key features:
  - Element-wise power: base[i]^exponent[i] for matching dimensions
  - Broadcasting support: scalar exponent applied to all base elements
  - Optimized derivative computation using analytical power rule
  - Configurable optimizations via static flags

#### Configuration Flags
- `enableCustomDelta`: Controls analytical vs numerical differentiation (default: true)
- `enableUniqueNonZeroOffset`: Memory optimization for sparse patterns (default: false)

#### Delta Computation
- Implements power rule: d/dx[u^v] = v*u^(v-1)*du/dx when v is constant
- Handles composite functions through chain rule
- Optimized for machine learning backpropagation scenarios
- Falls back to parent class delta for complex cases

#### Usage Patterns
- Accessed via CollectionFeatures.pow() method
- Used internally by CollectionProducer.pow() operations
- Supports multi-dimensional tensor operations
- Efficient for large-scale scientific computing

#### Development Notes
- All power operations should use CollectionExponentComputation for consistency
- Test edge cases: x^0, x^1, 1^x, fractional exponents, negative exponents
- Custom delta is critical for ML applications - avoid disabling unless necessary
- Memory patterns optimized for sequential access - avoid scattered indexing
