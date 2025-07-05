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
- Never use `() -> value.get()` or related patterns in example, use Producer or CollectionProducer instead


## Computation Classes

- Computation names should always be camelCase like method names (NOT snake_case)
- Computations may accept any Supplier of Evaluable as input, but when creating
  examples for documentation or tests, you should always use Producer (or
  CollectionProducer, if applicable) as an input since this is by far the most
  common case

### CollectionExponentComputation
- Implements element-wise exponentiation (power operations) on multi-dimensional collections
- Extends TraversableExpressionComputation for parallel processing capabilities
- Delta computation implements power rule for derivatives
- Available through CollectionFeatures.pow() and CollectionProducer.pow()

### RepeatedProducerComputationAdapter
- Implements Adapter pattern to convert TraversableExpression operations into RepeatedProducerComputation format
- Simplified destination addressing using localIndex directly
- Primary usage through toRepeated() methods in:
  - CollectionProducerComputationAdapter.toRepeated()
  - RelativeTraversableProducerComputation.toRepeated()

### ConstantRepeatedProducerComputation
- Extends RepeatedProducerComputation with fixed iteration counts
- Used primarily for reduction operations (e.g., finding max index in CollectionFeatures)
- Enables compiler optimizations through constant iteration bounds
- Constructor with memory length parameter (size) controls elements per kernel thread

### TraversableRepeatedProducerComputation
- Specialized RepeatedProducerComputation implementing TraversableExpression interface
- Fixed iteration count (constant) for optimization opportunities
- Traversable results enable composition with other traversable operations
- Isolation control based on iteration count threshold and memory requirements
- Common usage patterns:
  - Reduction operations (indexOfMax, sum accumulation)
  - Iterative refinement algorithms
  - Accumulation computations with known bounds
- Extended by AggregatedProducerComputation for aggregation operations
- Primary usage through CollectionFeatures.indexOfMax() method
