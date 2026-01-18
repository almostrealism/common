/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.scope;

import io.almostrealism.code.Array;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Mask;
import io.almostrealism.expression.SizeValue;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.uml.Multiple;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Represents a variable that holds an array of values within a {@link Scope}.
 *
 * <p>{@code ArrayVariable} extends {@link Variable} to provide array-specific functionality,
 * including element access via index expressions, offset calculations for delegate relationships,
 * and integration with the expression system for code generation. It implements the {@link Array}
 * interface to support array value access patterns.</p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><b>Element Access:</b> Access individual elements via {@link #valueAt(Expression)},
 *       {@link #reference(Expression)}, or {@link #getValueRelative(Expression)}</li>
 *   <li><b>Delegation:</b> Support for delegate relationships where one ArrayVariable can
 *       reference a portion of another via an offset</li>
 *   <li><b>Size Tracking:</b> Maintains array size information for bounds checking and
 *       code generation</li>
 *   <li><b>Lifecycle Management:</b> Support for destruction to prevent access to invalidated
 *       variables</li>
 * </ul>
 *
 * <h2>Physical Scope</h2>
 * <p>ArrayVariables can have different {@link PhysicalScope} values that affect how they
 * are handled during code generation:</p>
 * <ul>
 *   <li>{@link PhysicalScope#GLOBAL} - Variables accessible across the entire computation</li>
 *   <li>{@link PhysicalScope#LOCAL} - Variables with limited scope, typically within a kernel</li>
 * </ul>
 *
 * <h2>Delegate Relationships</h2>
 * <p>An ArrayVariable can delegate to another ArrayVariable with an offset, creating
 * a view into a portion of the delegate's data. When accessing elements through a
 * delegating variable, the offset is automatically applied:</p>
 * <pre>{@code
 * ArrayVariable<Double> base = new ArrayVariable<>(Double.class, "data", size);
 * ArrayVariable<Double> view = new ArrayVariable<>(base, new IntegerConstant(10));
 * // view[i] accesses base[i + 10]
 * }</pre>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Creating a Simple Array Variable</h3>
 * <pre>{@code
 * // Create a global array variable with explicit size
 * ArrayVariable<Double> data = new ArrayVariable<>(
 *     Double.class,
 *     "inputData",
 *     new IntegerConstant(1024)
 * );
 * }</pre>
 *
 * <h3>Creating with a Producer</h3>
 * <pre>{@code
 * // Create with a producer that provides the values
 * ArrayVariable<Double> computed = new ArrayVariable<>(
 *     "result",
 *     PhysicalScope.GLOBAL,
 *     Double.class,
 *     () -> someEvaluable
 * );
 * }</pre>
 *
 * <h3>Accessing Elements</h3>
 * <pre>{@code
 * // Get value at a specific index
 * Expression<Double> element = arrayVar.getValueRelative(5);
 *
 * // Get value using an expression index
 * Expression<Double> dynamic = arrayVar.valueAt(indexExpression);
 *
 * // Create an instance reference for assignments
 * InstanceReference<?, Double> ref = arrayVar.ref(offsetExpression);
 * }</pre>
 *
 * @param <T> the element type of the array (e.g., {@code Double}, {@code Float})
 *
 * @see Variable
 * @see Array
 * @see Scope
 * @see InstanceReference
 * @see PhysicalScope
 */
public class ArrayVariable<T> extends Variable<Multiple<T>, ArrayVariable<T>> implements Array<T, ArrayVariable<T>> {

	/**
	 * The offset into the delegate array when this variable is a view.
	 * When accessing elements through this variable, this offset is added
	 * to the requested index to compute the actual position in the delegate.
	 */
	private Expression<Integer> delegateOffset;

	/**
	 * The declared size of this array, if known.
	 * May be null if the size is determined dynamically at runtime.
	 */
	private Expression<Integer> arraySize;

	/**
	 * Flag indicating whether offset calculations should be disabled.
	 * When true, element references will not include offset adjustments
	 * during code generation.
	 */
	private boolean disableOffset;

	/**
	 * Flag indicating whether this variable has been destroyed.
	 * Once destroyed, most operations will throw {@link UnsupportedOperationException}.
	 */
	private boolean destroyed;

	/**
	 * Creates a new global array variable with the specified type, name, and size.
	 *
	 * <p>This constructor creates an array variable with {@link PhysicalScope#GLOBAL}
	 * scope and no producer. It is useful for declaring array variables that will
	 * receive data from external sources.</p>
	 *
	 * @param type      the element type class (e.g., {@code Double.class})
	 * @param name      the variable name used in generated code
	 * @param arraySize an expression representing the array size
	 */
	public ArrayVariable(Class<T> type, String name, Expression<Integer> arraySize) {
		this(PhysicalScope.GLOBAL, type, name, arraySize, null);
	}

	/**
	 * Creates a new array variable with full specification of all properties.
	 *
	 * <p>This is the most complete constructor, allowing specification of physical scope,
	 * element type, name, size, and an optional producer that can provide the array values.</p>
	 *
	 * @param scope     the physical scope ({@link PhysicalScope#GLOBAL} or {@link PhysicalScope#LOCAL})
	 * @param type      the element type class (e.g., {@code Double.class})
	 * @param name      the variable name used in generated code
	 * @param arraySize an expression representing the array size
	 * @param p         a supplier that produces an {@link Evaluable} for the array values,
	 *                  or null if values are provided externally
	 */
	public ArrayVariable(PhysicalScope scope,
						 Class<T> type, String name,
						 Expression<Integer> arraySize,
						 Supplier<Evaluable<? extends Multiple<T>>> p) {
		super(name, scope, type, p);
		setArraySize(arraySize);
	}

	/**
	 * Creates a new global array variable with a producer and default element type of {@code Double}.
	 *
	 * <p>This convenience constructor is useful when creating array variables that are
	 * backed by a producer, with the common case of Double-valued arrays.</p>
	 *
	 * @param name     the variable name used in generated code
	 * @param producer a supplier that produces an {@link Evaluable} for the array values
	 */
	public ArrayVariable(String name, Supplier<Evaluable<? extends Multiple<T>>> producer) {
		this(name, PhysicalScope.GLOBAL, Double.class, producer);
	}

	/**
	 * Creates a new array variable with a producer and specified scope.
	 *
	 * <p>This constructor allows specifying the physical scope along with a producer,
	 * but does not set an explicit array size (size may be determined by the producer).</p>
	 *
	 * @param name  the variable name used in generated code
	 * @param scope the physical scope ({@link PhysicalScope#GLOBAL} or {@link PhysicalScope#LOCAL})
	 * @param type  the element type class
	 * @param p     a supplier that produces an {@link Evaluable} for the array values
	 */
	public ArrayVariable(String name, PhysicalScope scope, Class<?> type,
						 Supplier<Evaluable<? extends Multiple<T>>> p) {
		super(name, scope, type, p);
	}

	/**
	 * Creates a new array variable that acts as an offset view into a delegate array.
	 *
	 * <p>This constructor creates an array variable that references a portion of another
	 * array variable starting at the specified offset. Element accesses through this
	 * variable will be translated to the delegate with the offset applied.</p>
	 *
	 * <p>Example: If the delegate offset is 10, accessing element 0 of this variable
	 * will access element 10 of the delegate.</p>
	 *
	 * @param delegate       the array variable to delegate to
	 * @param delegateOffset an expression representing the starting offset into the delegate
	 */
	public ArrayVariable(ArrayVariable<T> delegate, Expression<Integer> delegateOffset) {
		super(null, delegate.getPhysicalScope(), null, null);
		setDelegate(delegate);
		setDelegateOffset(delegateOffset);
	}

	/**
	 * Sets the size expression for this array.
	 *
	 * @param arraySize an expression representing the array size, or null for dynamic sizing
	 */
	public void setArraySize(Expression<Integer> arraySize) { this.arraySize = arraySize; }

	/**
	 * Returns the size expression for this array.
	 *
	 * @return the array size expression, or null if size is determined dynamically
	 * @throws UnsupportedOperationException if this variable has been destroyed
	 */
	public Expression<Integer> getArraySize() {
		if (destroyed) throw new UnsupportedOperationException();

		return arraySize;
	}

	/**
	 * Sets the delegate array variable for this view.
	 *
	 * <p>When a delegate is set, this variable becomes a view into the delegate's data.
	 * Element accesses will be redirected to the delegate with the configured offset applied.</p>
	 *
	 * @param delegate the array variable to delegate to, or null to remove delegation
	 */
	@Override
	public void setDelegate(ArrayVariable<T> delegate) {
		super.setDelegate(delegate);
	}

	/**
	 * Returns the offset expression used when accessing the delegate array.
	 *
	 * @return the delegate offset expression, or null if no delegate is set
	 */
	public Expression<Integer> getDelegateOffset() { return delegateOffset; }

	/**
	 * Sets the offset expression used when accessing the delegate array.
	 *
	 * @param delegateOffset an expression representing the offset into the delegate array
	 */
	public void setDelegateOffset(Expression<Integer> delegateOffset) { this.delegateOffset = delegateOffset; }

	/**
	 * Sets the delegate offset to a constant integer value.
	 *
	 * <p>This is a convenience method that wraps the integer in an {@link IntegerConstant}.</p>
	 *
	 * @param delegateOffset the constant offset value
	 */
	public void setDelegateOffset(int delegateOffset) { setDelegateOffset(new IntegerConstant(delegateOffset)); }

	/**
	 * Returns whether offset calculations are disabled for this variable.
	 *
	 * @return true if offset calculations are disabled, false otherwise
	 */
	public boolean isDisableOffset() { return disableOffset; }

	/**
	 * Sets whether offset calculations should be disabled for this variable.
	 *
	 * <p>When disabled, element references in generated code will not include offset
	 * adjustments, even if an offset value is defined.</p>
	 *
	 * @param disableOffset true to disable offset calculations, false to enable them
	 */
	public void setDisableOffset(boolean disableOffset) {
		this.disableOffset = disableOffset;
	}

	/**
	 * Computes the total offset from this variable to the root (non-delegating) array.
	 *
	 * <p>This method recursively traverses the delegate chain, summing all offsets
	 * to produce the total offset from this view to the underlying data storage.</p>
	 *
	 * @return the total offset as a constant integer value
	 * @throws UnsupportedOperationException if this variable has been destroyed
	 */
	public int getOffset() {
		if (destroyed) throw new UnsupportedOperationException();

		if (getDelegate() == null) {
			return 0;
		} else {
			return getDelegate().getOffset() + getDelegateOffset().intValue().getAsInt();
		}
	}

	/**
	 * Gets the value at a relative index position as a constant integer.
	 *
	 * <p>This is a convenience method that wraps the index in an {@link IntegerConstant}.</p>
	 *
	 * @param index the constant index relative to the current kernel position
	 * @return an expression representing the value at the specified relative index
	 */
	public Expression<Double> getValueRelative(int index) {
		return getValueRelative(new IntegerConstant(index));
	}

	/**
	 * Gets the value at a relative index position.
	 *
	 * <p>This method retrieves a value relative to the current kernel index. The actual
	 * position is calculated as: {@code kernelIndex * length + index}. If this variable
	 * has a delegate, the request is forwarded with the delegate offset applied.</p>
	 *
	 * <p>If a {@link TraversableExpression} is available from the producer, it will be
	 * consulted first to potentially provide an optimized expression.</p>
	 *
	 * @param index an expression representing the relative index
	 * @return an expression representing the value at the computed position
	 * @throws UnsupportedOperationException if this variable has been destroyed
	 */
	public Expression<Double> getValueRelative(Expression index) {
		if (destroyed) throw new UnsupportedOperationException();

		TraversableExpression exp = TraversableExpression.traverse(getProducer());

		if (exp != null) {
			Expression<Double> value = exp.getValueRelative(index);
			if (value != null) return value;
		}

		if (getDelegate() != null) {
			return getDelegate().getValueRelative(index.add(getDelegateOffset()));
		}

		return (Expression) reference(new KernelIndex().multiply(length()).add(index.toInt()));
	}

	/**
	 * Returns an expression for the value at the specified index position.
	 *
	 * <p>This method implements the {@link Array} interface to provide element access.
	 * It delegates to {@link #reference(Expression)} for the actual implementation.</p>
	 *
	 * @param exp an expression representing the index position
	 * @return an expression representing the value at the specified position
	 * @throws UnsupportedOperationException if this variable has been destroyed
	 */
	@Override
	public Expression<T> valueAt(Expression<?> exp) {
		if (destroyed) throw new UnsupportedOperationException();
		return reference(exp);
	}

	/**
	 * Creates an instance reference to this array with the specified offset.
	 *
	 * <p>This method creates a new ArrayVariable that is a view into this array
	 * starting at the given offset, then wraps it in an {@link InstanceReference}.
	 * This is useful for creating references that can be used in assignments.</p>
	 *
	 * @param offset an expression representing the offset into this array
	 * @return an instance reference to the offset view
	 * @throws UnsupportedOperationException if this variable has been destroyed
	 */
	public InstanceReference<Multiple<T>, T> ref(Expression<Integer> offset) {
		if (destroyed) throw new UnsupportedOperationException();
		return new InstanceReference<>(new ArrayVariable<>(this, offset));
	}

	/**
	 * Creates a reference expression for accessing an element at the specified position.
	 *
	 * <p>This method generates an {@link InstanceReference} expression that can be used
	 * in code generation to access the array element at the given position. If this
	 * variable delegates to another, the reference is redirected to the delegate with
	 * the offset applied.</p>
	 *
	 * <p>The generated reference may include bounds masking based on
	 * {@link ScopeSettings#enableInstanceReferenceMasking}. When enabled, the reference
	 * is wrapped in a {@link Mask} that checks if the index is non-negative.</p>
	 *
	 * @param pos an expression representing the position to access
	 * @return an expression representing the reference to the element
	 * @throws UnsupportedOperationException if this variable has been destroyed
	 * @throws IllegalArgumentException if this variable circularly delegates to itself
	 */
	public Expression<T> reference(Expression<?> pos) {
		if (destroyed) throw new UnsupportedOperationException();

		if (getDelegate() == this) {
			throw new IllegalArgumentException("Circular delegate reference");
		} else if (getDelegate() != null) {
			return getDelegate().reference(pos.add(getDelegateOffset()));
		}

		Expression<?> index = pos;
		Expression<Boolean> condition = index.greaterThanOrEqual(new IntegerConstant(0));

		pos = index.toInt();
		index = pos.imod(length());

		InstanceReference<?, T> ref = new InstanceReference<>(this, pos, index);
		return ScopeSettings.enableInstanceReferenceMasking ? Mask.of(condition, ref) : ref;
	}

	/**
	 * Returns a static reference expression for the offset value of this array.
	 *
	 * <p>This method generates a reference to a variable named "{name}Offset" that
	 * represents the runtime offset for this array. This is used during code generation
	 * when variable offsets need to be passed as parameters.</p>
	 *
	 * @return an expression representing the offset value reference
	 * @throws UnsupportedOperationException if this variable has been destroyed
	 */
	public Expression getOffsetValue() {
		if (destroyed) throw new UnsupportedOperationException();

		return new StaticReference<>(Integer.class, getName() + "Offset");
	}

	/**
	 * Returns an expression representing the length of this array.
	 *
	 * <p>This method creates a {@link SizeValue} expression that references this
	 * array variable. The actual size value is resolved during code generation.</p>
	 *
	 * @return an expression representing the array length
	 * @throws UnsupportedOperationException if this variable has been destroyed
	 */
	public Expression<Integer> length() {
		if (destroyed) throw new UnsupportedOperationException();

		return new SizeValue(this);
	}

	/**
	 * Compares this array variable with another object for equality.
	 *
	 * <p>Two ArrayVariables are considered equal if their parent {@link Variable}
	 * properties are equal and they have the same array size expression.</p>
	 *
	 * @param obj the object to compare with
	 * @return true if the objects are equal, false otherwise
	 */
	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof ArrayVariable)) return false;
		if (!super.equals(obj)) return false;
		return Objects.equals(getArraySize(), ((ArrayVariable) obj).getArraySize());
	}

	/**
	 * Marks this variable as destroyed, preventing further operations.
	 *
	 * <p>After calling this method, most operations on this variable will throw
	 * {@link UnsupportedOperationException}. This is used to invalidate variables
	 * that should no longer be accessed, helping to detect programming errors.</p>
	 */
	public void destroy() { this.destroyed = true; }
}
