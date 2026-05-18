/*
 * Copyright 2021 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.almostrealism.relation;

import java.util.ArrayList;
import java.util.List;

/**
 * An interface for types that delegate behavior to another object of the same type.
 *
 * <p>The {@link Delegated} pattern is used throughout the framework to create
 * wrapper objects that forward behavior to an underlying delegate. This enables:</p>
 * <ul>
 *   <li>Transparent proxying and decoration</li>
 *   <li>Lazy initialization patterns</li>
 *   <li>Runtime behavior modification</li>
 *   <li>Chains of responsibility</li>
 * </ul>
 *
 * <h2>Delegation Chains</h2>
 * <p>Delegates can themselves be {@link Delegated}, forming chains. This interface
 * provides methods to navigate and validate these chains:</p>
 * <ul>
 *   <li>{@link #getDelegate()} - Get the immediate delegate</li>
 *   <li>{@link #getRootDelegate()} - Traverse to the end of the chain</li>
 *   <li>{@link #getDelegateDepth()} - Count the chain length</li>
 *   <li>{@link #validateDelegate()} - Detect circular references</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class CachingProducer<T> implements Producer<T>, Delegated<Producer<T>> {
 *     private final Producer<T> delegate;
 *     private Evaluable<T> cached;
 *
 *     public CachingProducer(Producer<T> delegate) {
 *         this.delegate = delegate;
 *     }
 *
 *     @Override
 *     public Producer<T> getDelegate() { return delegate; }
 *
 *     @Override
 *     public Evaluable<T> get() {
 *         if (cached == null) cached = delegate.get();
 *         return cached;
 *     }
 * }
 * }</pre>
 *
 * <h2>Circular Delegation</h2>
 * <p>Circular delegation chains (A delegates to B delegates to A) are invalid
 * and will cause infinite loops. Use {@link #validateDelegate()} to detect
 * and prevent such configurations.</p>
 *
 * @param <T> the type of the delegate (typically the same as the implementing type)
 *
 * @see Producer
 *
 * @author Michael Murray
 */
public interface Delegated<T> {
	/**
	 * Returns the object this instance delegates to.
	 *
	 * <p>The delegate may be {@code null} if this instance does not currently
	 * delegate to anything (i.e., it is the end of a delegation chain).</p>
	 *
	 * @return the delegate object, or {@code null} if none
	 */
	T getDelegate();

	/**
	 * Traverses the delegation chain and returns the final non-delegating object.
	 *
	 * <p>If this instance has no delegate, returns {@code this}. Otherwise,
	 * recursively follows the delegation chain until reaching an object that
	 * either has no delegate or is not itself {@link Delegated}.</p>
	 *
	 * @return the root delegate at the end of the chain
	 */
	default T getRootDelegate() {
		if (getDelegate() == null) return (T) this;
		if (getDelegate() instanceof Delegated) return (T) ((Delegated) getDelegate()).getRootDelegate();
		return getDelegate();
	}

	/**
	 * Returns the depth of the delegation chain starting from this instance.
	 *
	 * <p>A depth of 0 means this instance has no delegate. A depth of 1 means
	 * this instance delegates to a non-delegating object. Greater depths indicate
	 * longer chains.</p>
	 *
	 * @return the number of delegation hops to the root
	 */
	default int getDelegateDepth() {
		if (getDelegate() == null) return 0;
		if (getDelegate() instanceof Delegated) return 1 + ((Delegated) getDelegate()).getDelegateDepth();
		return 1;
	}

	/**
	 * Validates that the delegation chain contains no circular references.
	 *
	 * <p>Circular delegation chains cause infinite loops and are considered
	 * invalid configurations.</p>
	 *
	 * @throws IllegalStateException if a circular delegation is detected
	 */
	default void validateDelegate() {
		validateDelegate(new ArrayList<>());
	}

	/**
	 * Validates the delegation chain for circular references using a tracking list.
	 *
	 * <p>This method is called recursively down the delegation chain, accumulating
	 * visited delegates in the provided list. If a delegate is encountered that
	 * already exists in the list, a circular reference has been detected.</p>
	 *
	 * @param existing the list of already-visited delegates
	 * @throws IllegalStateException if a circular delegation is detected
	 */
	default void validateDelegate(List<T> existing) {
		if (getDelegate() == null) return;

		if (existing.contains(getDelegate())) {
			throw new IllegalStateException("Circular delegation detected");
		}

		existing.add(getDelegate());
		if (getDelegate() instanceof Delegated) ((Delegated) getDelegate()).validateDelegate(existing);
	}
}
