/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.FixedEvaluable;
import io.almostrealism.uml.Multiple;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Function;

/**
 * A {@link DynamicCollectionProducer} of the collection most recently {@link #set(PackedCollection) set}
 * on it: a value the host chooses before each issue of an operation that uses it, such as the input of
 * a model pass, rather than one computed from the evaluation's arguments. An operation is compiled once
 * against the container, and each time it is issued it reads whichever collection is set at that moment.
 *
 * <p>Its evaluable is a {@link FixedEvaluable}, so evaluating it only returns the collection that is
 * set. An operation therefore binds that collection as it is issued, exactly as it binds a provided
 * collection: a kernel argument is not requested asynchronously (which starts a thread for every
 * request of an evaluable that cannot stream), and a copy of it is ordered after the work it depends
 * on by the backend rather than waiting for that work on the host before its source is resolved.
 * Unlike a {@link CollectionProviderProducer}, whose collection is fixed when the graph is built, a
 * container is not a provider, so no compiled operation depends on which collection is set.</p>
 *
 * <p>Only an existing collection may be set: it is read without regard to the work an operation is
 * ordered after, so its contents must not change until every operation issued with it has completed.</p>
 *
 * <pre>{@code
 * CollectionContainer input = container(shape(3));
 * Runnable doubled = a(cp(result), input.multiply(2.0)).get();
 *
 * input.set(first);
 * doubled.run();     // result = 2 * first
 *
 * input.set(second);
 * doubled.run();     // result = 2 * second, without compiling again
 * }</pre>
 *
 * @see org.almostrealism.collect.CollectionCreationFeatures#container(TraversalPolicy)
 * @author Michael Murray
 */
public class CollectionContainer extends DynamicCollectionProducer {
	/** The collection an operation using this container binds when it is issued. */
	private PackedCollection collection;

	/**
	 * Creates a container for collections of the given shape, with no collection set.
	 *
	 * @param shape the {@link TraversalPolicy} of the collections set on this container
	 */
	public CollectionContainer(TraversalPolicy shape) {
		super(shape, null);
	}

	/**
	 * Sets the collection that an operation using this container binds the next time it is issued.
	 *
	 * <p>An operation compiled against the container reads the collection with the container's
	 * {@link #getShape() shape}, so the shape of the container cannot be changed by what is set
	 * on it: a collection whose shape (including its number of dimensions) differs is rejected
	 * rather than read as if it had the container's shape, which would only hide the defect that
	 * produced it and fail, if at all, deeper in the graph.</p>
	 *
	 * @param collection the collection to bind, which may be {@code null} if no operation using
	 *                   this container is issued before another is set
	 * @throws IllegalArgumentException if the shape of {@code collection} differs from the shape
	 *                                  of this container, ignoring the traversal axis
	 */
	public void set(PackedCollection collection) {
		if (collection != null && !collection.getShape().equalsIgnoreAxis(getShape())) {
			throw new IllegalArgumentException("Collection shape mismatch: expected " + getShape() +
					" but received " + collection.getShape());
		}

		this.collection = collection;
	}

	/**
	 * Returns a function of the collection that is set, ignoring the evaluation's arguments.
	 *
	 * @return the function the evaluable of this container delegates to
	 */
	@Override
	protected Function<Object[], PackedCollection> getFunction() {
		return args -> collection;
	}

	/**
	 * Returns a {@link FixedEvaluable} of the collection that is set when it is evaluated. Destinations
	 * are created, and a destination is written, exactly as for any other {@link DynamicCollectionProducer}.
	 *
	 * @return an evaluable of the collection set on this container
	 */
	@Override
	public Evaluable<PackedCollection> get() {
		Evaluable<PackedCollection> dynamic = super.get();

		return new FixedEvaluable<PackedCollection>() {
			@Override
			public PackedCollection get() { return collection; }

			@Override
			public Multiple<PackedCollection> createDestination(int size) {
				return dynamic.createDestination(size);
			}

			@Override
			public Evaluable<PackedCollection> into(Object destination) {
				return dynamic.into(destination);
			}
		};
	}
}
