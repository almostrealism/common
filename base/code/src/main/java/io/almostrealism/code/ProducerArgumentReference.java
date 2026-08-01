package io.almostrealism.code;

/**
 * Marks a computation or expression as a direct reference to a specific argument of its enclosing producer.
 *
 * <p>When a producer computation references one of its own input arguments by index,
 * it implements {@code ProducerArgumentReference} to allow the code generator to emit
 * a direct variable reference rather than computing the value again.</p>
 *
 * @see io.almostrealism.code.Computation
 * @see io.almostrealism.scope.ArrayVariable
 */
public interface ProducerArgumentReference {
	/**
	 * Returns the zero-based index of the argument that this reference points to.
	 *
	 * @return the referenced argument index
	 */
	int getReferencedArgumentIndex();
}
