# Algebra Module Agent Memory

## IndexProjectionProducerComputation

### Overview
`IndexProjectionProducerComputation` is a core computation class for performing index transformation operations on multi-dimensional collections. It extends `TraversableExpressionComputation` and serves as the foundation for many advanced collection operations.

### Key Concepts
- **Index Projection**: Mathematical transformation that maps output indices to input indices
- **Usage Pattern**: Takes a collection + projection function → produces transformed collection
- **Applications**: Subsetting, permutation, enumeration, element selection, matrix operations

### Primary Use Cases
1. **Collection Subsetting**: Extract specific elements or sub-regions from collections
2. **Element Permutation**: Reorder elements according to custom patterns  
3. **Matrix Operations**: Row/column selection, transposition, block operations
4. **Advanced Indexing**: Complex access patterns for scientific computations

### Related Classes
- Base class: `TraversableExpressionComputation`
- Specialized version: `DynamicIndexProjectionProducerComputation` 
- Concrete implementations: `PackedCollectionSubset`, `CollectionPermute`, `PackedCollectionEnumerate`
- Expression layer: `IndexProjectionExpression`

### Implementation Details
- Supports automatic differentiation via delta computation
- Includes memory optimization features (delegated isolation)
- Handles nested projections efficiently  
- Provides index mapping matrix generation

### Documentation Status
- ✅ Comprehensive class-level javadoc with usage examples
- ✅ All public constructors documented with examples
- ✅ Key methods documented with parameter descriptions
- ✅ Related class references and @see tags added
- ✅ Advanced features and optimization strategies explained

## CollectionMinusComputation

### Overview
`CollectionMinusComputation` implements element-wise negation (unary minus) of collections. It extends `TransitiveDeltaExpressionComputation` and provides specialized support for automatic differentiation through delta computation.

### Key Concepts
- **Mathematical Operation**: Element-wise negation that multiplies each element by -1
- **Usage Pattern**: Typically used indirectly through CollectionFeatures.minus() or subtract() methods
- **Applications**: Mathematical expressions, gradient computations, sign inversion operations

### Primary Use Cases
1. **Direct Negation**: Convert positive values to negative and vice versa
2. **Subtraction Implementation**: Used internally for a - b = a + (-b) operations
3. **Gradient Computations**: Automatic differentiation in neural networks and optimization
4. **Mathematical Expressions**: Sign inversion in complex mathematical operations

### Performance Characteristics
- **Constants**: Optimized using AtomicConstantComputation for single values
- **Identity Matrices**: Special optimization using ScalarMatrixComputation
- **Large Collections**: Efficient kernel-based computation for multi-dimensional arrays
- **Delta Computation**: Optimized gradient propagation with constant -1 derivatives

### Integration Points
- Used by CollectionFeatures.minus() for high-level negation operations
- Used by CollectionFeatures.subtract() for element-wise subtraction
- Integrates with automatic differentiation framework via delta() method
- Supports parallel execution on CPU and GPU platforms

### Documentation Status
- ✅ Comprehensive class-level javadoc with mathematical explanation
- ✅ All three constructors documented with detailed parameters and examples
- ✅ Both methods (getExpression, generate) fully documented
- ✅ Usage examples for basic negation, mathematical expressions, and gradient computation
- ✅ Cross-references to related classes and integration points
- ✅ Performance characteristics and optimization strategies explained
- ✅ Proper @param, @return, @throws, and @see annotations throughout
