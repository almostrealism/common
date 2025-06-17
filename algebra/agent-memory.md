# Algebra Module Agent Memory

## Computation Classes

### ConstantRepeatedProducerComputation
- Extends RepeatedProducerComputation with fixed iteration counts
- Used primarily for reduction operations (e.g., finding max index in CollectionFeatures)
- Enables compiler optimizations through constant iteration bounds
- Has specialized delta computation via ConstantRepeatedDeltaComputation
- Constructor with memory length parameter (size) controls elements per kernel thread

### Documentation Patterns
- Use comprehensive examples showing practical applications
- Include performance considerations and optimization notes
- Document parameter relationships and their effects on performance
- Use @link for class references in javadoc
- Include code examples that demonstrate realistic usage patterns from the codebase
