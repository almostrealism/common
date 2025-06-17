# Algebra Module Agent Memory

## Key Components

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

## Development Notes
- All power operations should use CollectionExponentComputation for consistency
- Test edge cases: x^0, x^1, 1^x, fractional exponents, negative exponents
- Custom delta is critical for ML applications - avoid disabling unless necessary
- Memory patterns optimized for sequential access - avoid scattered indexing
