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
 * against the handle, and each time it is issued it reads whichever collection is set at that moment.
 *
 * <p>Its evaluable is a {@link FixedEvaluable}, so evaluating it only returns the collection that is
 * set. An operation therefore binds that collection as it is issued, exactly as it binds a provided
 * collection: a kernel argument is not requested asynchronously (which starts a thread for every
 * request of an evaluable that cannot stream), and a copy of it is ordered after the work it depends
 * on by the backend rather than waiting for that work on the host before its source is resolved.
 * Unlike a {@link CollectionProviderProducer}, whose collection is fixed when the graph is built, a
 * handle is not a provider, so no compiled operation depends on which collection is set.</p>
 *
 * <p>Only an existing collection may be set: it is read without regard to the work an operation is
 * ordered after, so its contents must not change until every operation issued with it has completed.</p>
 *
 * <pre>{@code
 * CollectionHandle input = handle(shape(3));
 * Runnable doubled = a(cp(result), input.multiply(2.0)).get();
 *
 * input.set(first);
 * doubled.run();     // result = 2 * first
 *
 * input.set(second);
 * doubled.run();     // result = 2 * second, without compiling again
 * }</pre>
 *
 * @see org.almostrealism.collect.CollectionCreationFeatures#handle(TraversalPolicy)
 * @author Michael Murray
 */
public class CollectionHandle extends DynamicCollectionProducer {
	/** The collection an operation using this handle binds when it is issued. */
	private PackedCollection collection;

	/**
	 * Creates a handle for collections of the given shape, with no collection set.
	 *
	 * @param shape the {@link TraversalPolicy} of the collections set on this handle
	 */
	public CollectionHandle(TraversalPolicy shape) {
		super(shape, null);
	}

	/**
	 * Sets the collection that an operation using this handle binds the next time it is issued.
	 * An operation compiled against the handle reads the collection with the handle's
	 * {@link #getShape() shape}, so the collection should have that shape; a handle may refuse
	 * one that does not.
	 *
	 * @param collection the collection to bind, which may be {@code null} if no operation using
	 *                   this handle is issued before another is set
	 * @throws IllegalArgumentException if this handle refuses the shape of {@code collection}
	 */
	public void set(PackedCollection collection) {
		this.collection = collection;
	}

	/**
	 * Returns a function of the collection that is set, ignoring the evaluation's arguments.
	 *
	 * @return the function the evaluable of this handle delegates to
	 */
	@Override
	protected Function<Object[], PackedCollection> getFunction() {
		return args -> collection;
	}

	/**
	 * Returns a {@link FixedEvaluable} of the collection that is set when it is evaluated. Destinations
	 * are created, and a destination is written, exactly as for any other {@link DynamicCollectionProducer}.
	 *
	 * @return an evaluable of the collection set on this handle
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
