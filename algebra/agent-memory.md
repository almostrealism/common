# Algebra Module Agent Memory

## Build

```shell
mvn package -pl algebra -DskipTests
```

Do NOT attempt to run the build for this module by navigating to the
module's directory as doing this will prevent the dependent modules
from being loaded and respected.

Always run `mvn` commands from the root of the repository.

## Test

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


## Computation Classes

- Computation names should always be camelCase like method names (NOT snake_case)
- Computations often accept any Supplier of Evaluable as input, but when creating
  examples for documentation or tests, you should always use Producer (or
  CollectionProducer, if applicable) as an input since this is by far the most
  common case

### ConstantRepeatedProducerComputation
- Extends RepeatedProducerComputation with fixed iteration counts
- Used primarily for reduction operations (e.g., finding max index in CollectionFeatures)
- Enables compiler optimizations through constant iteration bounds
- Has specialized delta computation via ConstantRepeatedDeltaComputation
- Constructor with memory length parameter (size) controls elements per kernel thread

### CollectionSumComputation
- Primary implementation for element-wise addition of PackedCollection instances
- Extends TransitiveDeltaExpressionComputation for automatic differentiation support
- Used internally by CollectionFeatures.add() methods
- Supports variable number of operands through varargs constructors
- Key methods:
  - `getExpression()`: Creates sum expression using ExpressionFeatures.sum()
  - `generate()`: Creates CollectionProducerParallelProcess for hardware execution
- Examples and usage patterns documented in comprehensive javadoc
- Optimized for parallel execution and hardware acceleration

### CollectionExponentComputation
- Implements element-wise exponentiation (power operations) on multi-dimensional collections
- Extends TraversableExpressionComputation for parallel processing capabilities
- Key features:
  - Element-wise power: base[i]^exponent[i] for matching dimensions
  - Broadcasting support: scalar exponent applied to all base elements
  - Optimized derivative computation using analytical power rule
- Delta computation implements power rule for derivatives
- Available through CollectionFeatures.pow() and CollectionProducer.pow()
