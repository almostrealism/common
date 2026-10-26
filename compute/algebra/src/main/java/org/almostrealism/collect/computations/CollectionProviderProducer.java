/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.uml.Signature;
import io.almostrealism.util.DescribableParent;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A {@link Producer} wrapper that provides {@link Shape} values (typically {@link PackedCollection}s)
 * as constant sources in computational graphs.
 *
 * <p>This class implements {@link CollectionProducerBase} and {@link Process} to integrate
 * {@link Shape} values (including {@link PackedCollection}s) into the producer/process framework.
 * It acts as a leaf node in computational graphs, providing a fixed value with no dependencies.</p>
 *
 * <h2>Purpose and Role</h2>
 * <p>{@code CollectionProviderProducer} serves as the bridge between static data values and
 * the dynamic computational graph system. It wraps a {@link Shape} value (most commonly a
 * {@link PackedCollection}) and exposes it through the {@link Producer} interface for use
 * in computations.</p>
 *
 * <h2>Provider Selection</h2>
 * <p>The {@link #get()} method intelligently selects the appropriate provider type:</p>
 * <ul>
 *   <li><strong>{@link PackedCollection}:</strong> Uses {@link CollectionProvider} for efficient memory copying</li>
 *   <li><strong>Other {@link Shape} types:</strong> Uses generic {@link Provider}</li>
 * </ul>
 *
 * <h2>Common Use Cases</h2>
 * <ul>
 *   <li><strong>Model Parameters:</strong> Providing weights, biases, embeddings as graph inputs</li>
 *   <li><strong>Constant Data:</strong> Fixed lookup tables, configuration tensors</li>
 *   <li><strong>Initialization:</strong> Initial states for recurrent computations</li>
 *   <li><strong>Testing:</strong> Known input data for validation and debugging</li>
 * </ul>
 *
 * <h2>Signature and Identity</h2>
 * <p>The {@link #signature()} method generates unique identifiers for the provided value:</p>
 * <ul>
 *   <li><strong>{@link MemoryData}:</strong> {@code offset:memLength|shape} (includes memory location)</li>
 *   <li><strong>Aggregation Targets:</strong> {@code null} (signature depends on computation context)</li>
 *   <li><strong>Other Shapes:</strong> {@code |shape} (shape only)</li>
 * </ul>
 *
 * <h2>Ordered Values</h2>
 * <p>When the value's shape carries a {@link TraversalOrdering} that reads a collection in
 * order to map an index (a lookup table or a mask), that collection must be delivered to any
 * kernel that reads the value. As a {@link ScopeLifecycle} participant, this producer
 * {@link TraversalOrdering#bind(ArgumentProvider) binds} the ordering to the kernel's arguments
 * in {@link #prepareScope(ArgumentProvider, KernelStructureContext)}, before the argument
 * variable for its value is created, and exposes the bound ordering through
 * {@link #getOrdering()} until {@link #resetArguments()}. The argument variable captures the
 * bound ordering when it is created, so a kernel keeps reading through its own arguments even
 * if this producer is later prepared for another kernel. The {@link #getShape() shape} is
 * unaffected, so the value reads the same way on the host.</p>
 *
 * <h2>Equality and Hashing</h2>
 * <p>Two {@code CollectionProviderProducer} instances are considered equal if they provide
 * the same {@link Shape} instance (reference equality). This ensures that the same data
 * source is recognized as identical across the computational graph.</p>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Providing model weights:</strong></p>
 * <pre>{@code
 * PackedCollection weights = new PackedCollection(shape(128, 64));
 * weights.randFill();  // then scale via a producer
 *
 * CollectionProviderProducer<PackedCollection> weightsProducer =
 *     new CollectionProviderProducer<>(weights);
 *
 * // Use in computation graph
 * CollectionProducer<?> output = input.multiply(weightsProducer);
 * }</pre>
 *
 * <p><strong>Traversing and reshaping:</strong></p>
 * <pre>{@code
 * PackedCollection data = new PackedCollection(shape(10, 5, 3));
 * CollectionProviderProducer<PackedCollection> dataProducer =
 *     new CollectionProviderProducer<>(data);
 *
 * // Traverse along axis 1 (5 elements)
 * Producer<PackedCollection> traversed = dataProducer.traverse(1);
 *
 * // Reshape to different dimensions
 * Producer<PackedCollection> reshaped = dataProducer.reshape(shape(50, 3));
 * }</pre>
 *
 * <p><strong>Checking identity in graph analysis:</strong></p>
 * <pre>{@code
 * PackedCollection sharedData = new PackedCollection(shape(100));
 * CollectionProviderProducer<?> producer1 = new CollectionProviderProducer<>(sharedData);
 * CollectionProviderProducer<?> producer2 = new CollectionProviderProducer<>(sharedData);
 *
 * // Producers are equal because they provide the same instance
 * assert producer1.equals(producer2);
 * assert producer1.hashCode() == producer2.hashCode();
 * }</pre>
 *
 * <h2>Comparison with Related Classes</h2>
 * <ul>
 *   <li><strong>CollectionProviderProducer:</strong> Producer interface for Shape values (this class)</li>
 *   <li><strong>{@link CollectionProvider}:</strong> Evaluable provider for PackedCollection</li>
 *   <li><strong>{@link Provider}:</strong> Generic evaluable provider for any type</li>
 *   <li><strong>{@link CollectionConstantComputation}:</strong> Generates constant values (not stored)</li>
 * </ul>
 *
 * <h2>Process Graph Characteristics</h2>
 * <ul>
 *   <li><strong>Children:</strong> None (leaf node in process graph)</li>
 *   <li><strong>Isolation:</strong> Returns self (already isolated)</li>
 *   <li><strong>Generation:</strong> Returns self (no children to regenerate)</li>
 *   <li><strong>Metadata:</strong> Includes shape details for profiling and analysis</li>
 * </ul>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><strong>Memory:</strong> Stores reference to existing Shape (no copying)</li>
 *   <li><strong>Evaluation:</strong> O(1) provider creation</li>
 *   <li><strong>Traversal/Reshape:</strong> O(1) wrapper creation using helper methods</li>
 *   <li><strong>Signature Generation:</strong> O(1) for most cases</li>
 * </ul>
 *
 * @param <T> The type of {@link Shape} this producer provides
 *
 * @see CollectionProvider
 * @see Provider
 * @see CollectionProducerBase
 * @see Shape
 * @see PackedCollection
 *
 * @author Michael Murray
 */
public class CollectionProviderProducer<T extends Shape>
		implements CollectionProducerBase<T, Producer<T>>,
				Process<Process<?, ?>, Evaluable<? extends T>>,
				OperationInfo, Signature, DescribableParent<Process<?, ?>>,
				ScopeLifecycle, CollectionFeatures {
	/**
	 * Metadata for profiling and operation tracking, including the operation name
	 * and shape details.
	 */
	private final OperationMetadata metadata;

	/**
	 * The {@link Shape} value (typically a {@link PackedCollection}) that this
	 * producer provides.
	 */
	private final Shape value;

	/**
	 * The ordering of the value's shape, {@link TraversalOrdering#bind(ArgumentProvider) bound}
	 * to the arguments of the kernel being compiled, or {@code null} if the scope has not been
	 * prepared since the arguments were last reset.
	 */
	private TraversalOrdering boundOrder;

	/**
	 * Constructs a provider producer for the specified {@link Shape} value.
	 *
	 * <p>This constructor initializes the operation metadata with descriptive
	 * information about the provided value, including its shape details. The
	 * metadata is used for profiling, debugging, and operation tracking.</p>
	 *
	 * @param value The {@link Shape} value (typically a {@link PackedCollection}) to provide
	 */
	public CollectionProviderProducer(Shape value) {
		this.metadata = new OperationMetadata("collection", OperationInfo.name(value),
				"Provide a collection " + value.getShape().toStringDetail());
		this.value = value;
	}

	/**
	 * Returns the operation metadata for profiling and tracking.
	 *
	 * <p>The metadata includes the operation type ("collection"), the value's name
	 * (if available), and a detailed description of the shape being provided.</p>
	 *
	 * @return The {@link OperationMetadata} for this provider operation
	 */
	@Override
	public OperationMetadata getMetadata() {
		return metadata;
	}

	/**
	 * Returns an {@link Evaluable} that provides the wrapped {@link Shape} value.
	 *
	 * <p>This method intelligently selects the appropriate provider implementation:</p>
	 * <ul>
	 *   <li>If the value is a {@link PackedCollection}, returns a {@link CollectionProvider}
	 *       for efficient memory copying support</li>
	 *   <li>Otherwise, returns a generic {@link Provider}</li>
	 * </ul>
	 *
	 * @return A {@link CollectionProvider} if value is PackedCollection, otherwise a {@link Provider}
	 */
	@Override
	public Evaluable get() {
		return value instanceof PackedCollection ?
				new CollectionProvider((PackedCollection) value) : new Provider(value);
	}

	/**
	 * Returns the {@link TraversalPolicy} shape of the provided value.
	 *
	 * <p>This delegates to the wrapped {@link Shape} value's {@code getShape()} method,
	 * exposing the shape information through the {@link Producer} interface.</p>
	 *
	 * @return The {@link TraversalPolicy} of the provided value
	 */
	@Override
	public TraversalPolicy getShape() { return value.getShape(); }

	@Override
	public boolean isProvider() { return true; }

	/**
	 * Returns the ordering through which generated code reads the provided value: the ordering
	 * of its shape, {@link TraversalOrdering#bind(ArgumentProvider) bound} to the kernel's
	 * arguments if {@link #prepareScope(ArgumentProvider, KernelStructureContext)} has bound it.
	 *
	 * @return the ordering applied to reads of the value, or {@code null} if it has none
	 */
	@Override
	public TraversalOrdering getOrdering() {
		return boundOrder == null ? getShape().getOrder() : boundOrder;
	}

	/**
	 * Binds the ordering of the value's shape to the arguments supplied by the given manager,
	 * so that any collection the ordering reads in order to map an index is delivered to the
	 * kernel. A value whose shape has no ordering depends on no other argument. Binding is
	 * {@link TraversalOrdering#bind(ArgumentProvider) idempotent}, so the ordering is bound
	 * again each time the scope is prepared: the same manager yields an ordering that reads
	 * the same argument, and the manager of another compilation yields one that reads its own.
	 *
	 * @param manager the provider of the kernel's arguments
	 * @param context the kernel structure context for the compilation
	 */
	@Override
	public void prepareScope(ArgumentProvider manager, KernelStructureContext context) {
		TraversalOrdering order = getShape().getOrder();
		boundOrder = order == null ? null : order.bind(manager);
	}

	/**
	 * Discards the ordering bound by {@link #prepareScope(ArgumentProvider, KernelStructureContext)},
	 * since it refers to the arguments of the compilation that prepared it.
	 */
	@Override
	public void resetArguments() {
		boundOrder = null;
	}

	/**
	 * Creates a traversal view of this producer along the specified axis.
	 *
	 * <p>Delegates to the {@link CollectionFeatures#traverse(int, Producer)} helper method
	 * to create a traversed view without modifying this producer.</p>
	 *
	 * @param axis The axis index to traverse along
	 * @return A {@link Producer} representing the traversal view
	 */
	@Override
	public Producer<T> traverse(int axis) {
		return (Producer<T>) traverse(axis, (Producer<PackedCollection>) this);
	}

	/**
	 * Creates a reshaped view of this producer with the specified shape.
	 *
	 * <p>Delegates to the {@link CollectionFeatures#reshape(TraversalPolicy, Producer)} helper
	 * method to create a reshaped view without modifying this producer.</p>
	 *
	 * @param shape The target {@link TraversalPolicy} for reshaping
	 * @return A {@link Producer} representing the reshaped view
	 */
	@Override
	public Producer<T> reshape(TraversalPolicy shape) {
		return (Producer<T>) reshape(shape, this);
	}

	/**
	 * Returns this producer as an isolated process.
	 *
	 * <p>Provider producers are already leaf nodes with no dependencies, so they
	 * are inherently isolated and this method simply returns {@code this}.</p>
	 *
	 * @return This producer (already isolated)
	 */
	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
		return this;
	}

	/**
	 * Returns the child processes of this producer.
	 *
	 * <p>Provider producers are leaf nodes in the process graph with no children,
	 * so this always returns an empty collection.</p>
	 *
	 * @return An empty collection (no children)
	 */
	@Override
	public Collection<Process<?, ?>> getChildren() { return Collections.emptyList(); }

	/**
	 * Returns {@code true}, since a provider introduces its value into the graph
	 * rather than deriving it from inputs.
	 *
	 * @return {@code true}
	 */
	@Override
	public boolean isMatchRoot() { return true; }

	/**
	 * Generates a process with the specified child processes.
	 *
	 * <p>Since provider producers have no children, this method ignores the children
	 * parameter and returns {@code this} unchanged.</p>
	 *
	 * @param children List of child processes (ignored)
	 * @return This producer (no children to regenerate)
	 */
	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> generate(List<Process<?, ?>> children) {
		return this;
	}

	/**
	 * Generates a unique signature string identifying this provider's value and shape.
	 *
	 * <p>The signature format depends on the type of value being provided:</p>
	 * <ul>
	 *   <li><strong>MemoryData:</strong> Returns {@code "offset:memLength|shapeDetails"}
	 *       including memory location information</li>
	 *   <li><strong>Other Shapes:</strong> Returns {@code "|shapeDetails"} with only shape information</li>
	 * </ul>
	 *
	 * <p>The signature is used for process graph analysis, caching, and identifying
	 * equivalent producers.</p>
	 *
	 * <p>When the value is eligible for argument aggregation, the signature additionally encodes
	 * the root delegate's length as {@code &aggRoot=<length>}. A compiled kernel that aggregates
	 * bakes in the aggregate layout, which depends on the size of the root delegate folded into
	 * the shared buffer; two otherwise-identical producers whose roots differ in size (e.g. a
	 * 2-element view of a 2-element root vs. of an 8-element root) fold to different layouts and
	 * must not share a compiled kernel. Encoding the root length scopes instruction reuse to
	 * compatible aggregate layouts.</p>
	 *
	 * <p>When the shape carries a {@link TraversalOrdering}, the signature includes the
	 * {@link TraversalOrdering#signature() ordering's signature}, because the ordering is
	 * compiled into every kernel that reads the value and shape equality does not compare it.
	 * An ordering without a signature (one that reads a collection delivered to the kernel as an
	 * argument) leaves this producer without one, so no kernel that reads it is reused.</p>
	 *
	 * @return A signature string, or {@code null} if kernels reading this value must not be reused
	 */
	@Override
	public String signature() {
		TraversalOrdering order = value.getShape().getOrder();
		String ordering = order == null ? "" : order.signature();
		if (ordering == null) return null;

		String shape = "|" + value.getShape().toStringDetail() + (ordering.isEmpty() ? "" : "|order=" + ordering);

		if (value instanceof MemoryData) {
			MemoryData md = (MemoryData) value;
			String sig = md.getOffset() + ":" + md.getMemLength() + shape;

			// Aggregation-eligible: scope reuse to compatible aggregate layouts (see javadoc).
			if (MemoryDataArgumentMap.isAggregationTarget(md.getRootDelegate())) {
				sig = sig + "&aggRoot=" + md.getRootDelegate().getMemLength();
			}

			return sig;
		}

		return shape;
	}

	/**
	 * Returns a detailed description of this provider producer.
	 *
	 * <p>The description includes the shape's detailed description wrapped in a
	 * "p(...)" format to indicate this is a provider.</p>
	 *
	 * @return A string in the format {@code "p(shapeDescription)"}
	 */
	@Override
	public String describe() { return "p(" + getShape().describe() + ")"; }

	/**
	 * Returns a concise description of this provider producer.
	 *
	 * <p>The description includes the shape's string representation prefixed with
	 * "p" to indicate this is a provider.</p>
	 *
	 * @return A string in the format {@code "pshapeString"}
	 */
	@Override
	public String description() { return "p" + getShape().toString(); }

	/**
	 * Compares this provider producer with another object for equality.
	 *
	 * <p>Two {@code CollectionProviderProducer} instances are equal if they provide
	 * the same {@link Shape} instance (reference equality). This ensures that the
	 * same data source is recognized as identical across the computational graph.</p>
	 *
	 * @param obj The object to compare with
	 * @return {@code true} if the objects are the same instance or provide the same value instance
	 */
	@Override
	public boolean equals(Object obj) {
		if (super.equals(obj)) return true;
		if (!(obj instanceof CollectionProviderProducer)) return false;
		return ((CollectionProviderProducer) obj).value == value;
	}

	/**
	 * Returns {@code true} if {@code other} provides the same value as this producer. Besides
	 * the identical value recognized by {@link #equals(Object)}, this includes a different
	 * view of the same memory that {@link MemoryData#hasSameElements has the same elements},
	 * such as a reshape of it, so that a derivative taken with respect to
	 * {@code cp(collection)} also reaches a computation that reads
	 * {@code cp(collection.reshape(...))}.
	 *
	 * @param other the computable to compare with
	 * @return {@code true} if both producers provide the same elements
	 */
	@Override
	public boolean matches(Computable other) {
		if (equals(other)) return true;
		if (!(other instanceof CollectionProviderProducer)) return false;

		Shape otherValue = ((CollectionProviderProducer<?>) other).value;
		return value instanceof MemoryData && otherValue instanceof MemoryData
				&& ((MemoryData) value).hasSameElements((MemoryData) otherValue);
	}

	/**
	 * Returns the hash code for this provider producer.
	 *
	 * <p>The hash code is based on the provided {@link Shape} value's hash code.
	 * If the value is {@code null}, delegates to the superclass implementation.</p>
	 *
	 * @return The hash code of the provided value, or superclass hash code if value is null
	 */
	@Override
	public int hashCode() {
		return value == null ? super.hashCode() : value.hashCode();
	}
}
