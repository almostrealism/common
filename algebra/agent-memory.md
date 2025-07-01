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

### CollectionExponentComputation
- Implements element-wise exponentiation (power operations) on multi-dimensional collections
- Extends TraversableExpressionComputation for parallel processing capabilities
- Key features:
  - Element-wise power: base[i]^exponent[i] for matching dimensions
  - Broadcasting support: scalar exponent applied to all base elements
  - Optimized derivative computation using analytical power rule
- Delta computation implements power rule for derivatives
- Available through CollectionFeatures.pow() and CollectionProducer.pow()

### RepeatedProducerComputationAdapter
- Implements Adapter pattern to convert TraversableExpression operations into RepeatedProducerComputation format
- Key characteristics:
  - Fixed initialization to 0.0 (works because expression evaluation replaces initial values)
  - Condition: iterate while index < array length
  - Expression: evaluates provided TraversableExpression at each index
  - Simplified destination addressing using localIndex directly
- Primary usage through toRepeated() methods in:
  - CollectionProducerComputationAdapter.toRepeated()
  - RelativeTraversableProducerComputation.toRepeated()
- Enables bridge between traversable expressions and repeated computation pipelines
- Performance benefits: sequential processing, reduced memory requirements, kernel optimizations
- Common use cases: element-wise operations, computation strategy standardization, pipeline integration

### TraversableRepeatedProducerComputation
- Specialized RepeatedProducerComputation implementing TraversableExpression interface
- Enables efficient index-based value access during repeated iterations
- Key characteristics:
  - Fixed iteration count (constant) for optimization opportunities
  - Traversable results enable composition with other traversable operations
  - Isolation control based on iteration count threshold and memory requirements
  - Expression flattening at each iteration for kernel optimization
- Common usage patterns:
  - Reduction operations (indexOfMax, sum accumulation)
  - Iterative refinement algorithms
  - Accumulation computations with known bounds
- Performance considerations:
  - Isolation threshold (isolationCountThreshold = 16) for resource management
  - Memory checks against MemoryProvider.MAX_RESERVATION
  - Expression generation and flattening for GPU/parallel execution
- Extended by AggregatedProducerComputation for aggregation operations
- Primary usage through CollectionFeatures.indexOfMax() method
