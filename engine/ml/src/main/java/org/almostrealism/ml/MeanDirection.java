/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.collect.PackedCollection;

/**
 * The mean direction of a set of embeddings, accumulated on the device one embedding at a time:
 * the normalized sum of the normalized embeddings {@link #add added} since the last
 * {@link #clear()}.
 *
 * <p>This is how a contrastive model combines several embeddings of one thing into one, such as
 * the windows of a long clip or the captions of one tag: each embedding contributes its direction
 * only, and the result is a unit vector. No embedding is read back to the host.</p>
 *
 * <p>An embedding is usually the output buffer of a compiled model, the same buffer on every run,
 * so the assignment that adds it is compiled for that buffer and compiled again only when a
 * different buffer is added.</p>
 */
public class MeanDirection implements VectorFeatures, Destroyable {

	/** The shape of every embedding and of the result. */
	private final TraversalPolicy vector;

	/** The sum of the normalized embeddings added since the last {@link #clear()}. */
	private final PackedCollection sum;

	/** The normalized {@link #sum}, written by {@link #direction()}. */
	private final PackedCollection direction;

	/** The compiled assignment that writes the normalized {@link #sum} to {@link #direction}. */
	private final Runnable finish;

	/** The embedding buffer {@link #accumulate} reads. */
	private PackedCollection accumulated;

	/** The compiled assignment that adds the normalized {@link #accumulated} buffer to {@link #sum}. */
	private Runnable accumulate;

	/**
	 * Creates an empty mean of embeddings of the given width.
	 *
	 * @param width the number of values of every embedding
	 */
	public MeanDirection(int width) {
		this.vector = shape(width);
		this.sum = new PackedCollection(vector);
		this.sum.clear();
		this.direction = new PackedCollection(vector);
		this.finish = a("meanDirection", p(direction), normalize(cp(sum))).get();
	}

	/** Discards every embedding added so far. */
	public void clear() {
		sum.clear();
	}

	/**
	 * Adds the direction of one embedding.
	 *
	 * @param embedding the embedding, of the width of this mean in any shape
	 */
	public void add(PackedCollection embedding) {
		if (embedding != accumulated) {
			accumulate = a("meanDirectionSum", p(sum),
					cp(sum).add(normalize(cp(embedding).reshape(vector)))).get();
			accumulated = embedding;
		}

		accumulate.run();
	}

	/**
	 * Computes the mean direction of the embeddings added since the last {@link #clear()}.
	 *
	 * @return the unit-length direction, a buffer this mean owns and overwrites on the next call
	 */
	public PackedCollection direction() {
		finish.run();
		return direction;
	}

	@Override
	public void destroy() {
		sum.destroy();
		direction.destroy();
		accumulated = null;
		accumulate = null;
	}
}
