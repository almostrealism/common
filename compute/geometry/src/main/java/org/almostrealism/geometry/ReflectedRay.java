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

package org.almostrealism.geometry;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.TraversableExpressionComputation;

import java.util.List;

/**
 * Computes the ray reflected at an intersection point, given the direction toward the viewer
 * and the surface normal. This is used in ray tracing to calculate specular reflections.
 *
 * <p>The reflected direction is {@code R = I - 2(N . I)N / |N|^2}, where {@code I} is the
 * incident direction of travel (toward the surface), computed by
 * {@link GeometryFeatures#reflect(Producer, Producer, double)} from the viewer vector
 * {@code -I}.</p>
 *
 * <p>{@link ReflectedRay} is a computation of shape {@code (6)}, the layout of a
 * {@link Ray} (origin then direction), so it composes with {@link RayFeatures#origin(Producer)},
 * {@link RayFeatures#direction(Producer)} and every surface's {@code intersectAt}, and it
 * compiles into the kernel of whatever consumes it.</p>
 *
 * <p>A non-zero blur produces glossy reflections by randomly perturbing the reflected
 * direction (see {@link GeometryFeatures#reflect(Producer, Producer, double)}). A blur of 0.0
 * produces a perfect mirror reflection.</p>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * ReflectedRay reflection = new ReflectedRay(hitPoint, viewerDirection, normal, 0.0);
 * CollectionProducer bounceOrigin = origin(reflection);
 * ContinuousField hit = floor.intersectAt(reflection);
 * }</pre>
 *
 * @author Michael Murray
 * @see Ray
 * @see GeometryFeatures#reflect(Producer, Producer, double)
 */
public class ReflectedRay extends TraversableExpressionComputation {
	/** The shape of a ray: an origin followed by a direction. */
	private static final TraversalPolicy RAY_SHAPE = new TraversalPolicy(6);

	/**
	 * Constructs a ReflectedRay computation.
	 *
	 * @param point the intersection point where the reflection occurs
	 * @param viewer the vector from the intersection point toward the viewer, which is the
	 *               negation of the incident direction of travel
	 * @param normal the surface normal at the intersection point
	 * @param blur the amount of random perturbation for glossy reflections (0.0 = perfect mirror)
	 */
	public ReflectedRay(Producer<PackedCollection> point, Producer<PackedCollection> viewer,
						Producer<PackedCollection> normal, double blur) {
		this(point, GeometryFeatures.getInstance().reflect(viewer, normal, blur));
	}

	/**
	 * Constructs a ReflectedRay from its origin and its already reflected direction, the two
	 * operands of the computation.
	 *
	 * @param point     the origin of the reflected ray
	 * @param direction the reflected direction of travel
	 */
	protected ReflectedRay(Producer<PackedCollection> point, Producer<PackedCollection> direction) {
		super("reflectedRay", RAY_SHAPE, point, direction);
	}

	/**
	 * Lays out the ray: elements 0 to 2 are the origin and elements 3 to 5 are the reflected
	 * direction.
	 *
	 * <p>The index is the flattened output index, so when the computation is evaluated over a
	 * batch it addresses ray {@code idx / 6} at element {@code idx % 6}; that ray's origin and
	 * direction are the vectors starting at element {@code 3 * (idx / 6)} of each operand.</p>
	 *
	 * @param args the output followed by the origin and the reflected direction
	 * @return the expression for the reflected ray
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return CollectionExpression.create(getShape(), idx -> {
			Expression<?> element = idx.imod(6);
			Expression<?> vector = idx.divide(6).multiply(3);
			return Conditional.of(element.lessThan(3),
					args[1].getValueAt(vector.add(element)),
					args[2].getValueAt(vector.add(element).subtract(new IntegerConstant(3))));
		});
	}

	/**
	 * Creates a {@link ReflectedRay} over the given children, preserving the configuration
	 * of this computation.
	 *
	 * @param children the output followed by the origin and the reflected direction
	 * @return the new {@link ReflectedRay}
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return new ReflectedRay((Producer<PackedCollection>) children.get(1),
				(Producer<PackedCollection>) children.get(2))
				.setPostprocessor(getPostprocessor())
				.setDescription(getDescription())
				.setShortCircuit(getShortCircuit())
				.addAllDependentLifecycles(getDependentLifecycles());
	}
}
