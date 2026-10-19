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

package org.almostrealism.hardware.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.uml.Signature;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.algebra.computations.WeightedSumComputation;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionMaxComputation;
import org.almostrealism.collect.computations.CollectionSumComputation;
import org.almostrealism.collect.computations.IndexOfMaxComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.function.Supplier;

/**
 * Tests that aggregation-based computation graphs produce sound signatures,
 * so structurally identical graphs share one compiled kernel through the
 * signature-keyed instruction cache while structurally different graphs
 * never collide.
 *
 * <p>The projection graph here mirrors the genome projection in
 * {@code org.almostrealism.heredity.ProjectedGene}: a matrix-vector product
 * followed by element-wise wave shaping and a range mapping read from subset
 * columns.</p>
 */
public class AggregationSignatureTests extends TestSuiteBase {

	/**
	 * Verifies the signature of each stage of a projection graph over fixed
	 * collections, and that rebuilding the identical graph reproduces the
	 * identical signature.
	 */
	@Test(timeout = 60000)
	public void projectionGraphSignatures() {
		int len = 6;
		int sourceLength = 16;

		PackedCollection weights = new PackedCollection(shape(len, sourceLength)).traverse(1).randFill();
		PackedCollection source = new PackedCollection(shape(sourceLength)).randFill();
		PackedCollection ranges = new PackedCollection(shape(len, 2)).traverse(1).randFill();
		PackedCollection values = new PackedCollection(shape(len));

		Supplier<Runnable> op = projectionAssignment(len, sourceLength, weights, source, ranges, values);
		String signature = Signature.of(op);
		log("assignment signature = " + signature);
		assertNotNull("Projection graph assignment should have a signature", signature);

		Supplier<Runnable> rebuilt = projectionAssignment(len, sourceLength, weights, source, ranges, values);
		assertEquals(signature, Signature.of(rebuilt));
	}

	/**
	 * Builds the projection graph over the provided collections, mirroring
	 * {@code ProjectedGene.refreshValues}.
	 *
	 * @param len the number of projected values
	 * @param sourceLength the length of the source vector
	 * @param weights the projection weights
	 * @param source the source vector
	 * @param ranges the value range bounds
	 * @param values the destination collection
	 * @return the assignment operation for the projection
	 */
	private Supplier<Runnable> projectionAssignment(int len, int sourceLength,
													PackedCollection weights, PackedCollection source,
													PackedCollection ranges, PackedCollection values) {
		CollectionProducer projected = MatrixFeatures.getInstance()
				.matmul(cp(weights).reshape(shape(len, sourceLength)), cp(source))
				.reshape(shape(len));

		CollectionProducer value = mod(mod(projected, c(2.0)).add(c(2.0)), c(2.0));
		CollectionProducer phase = value.divide(c(2.0));
		CollectionProducer wave = greaterThan(phase, c(0.5),
				c(2.0).subtract(phase.multiply(c(2.0))),
				phase.multiply(c(2.0)));

		CollectionProducer bounds = cp(ranges).reshape(shape(len, 2));
		CollectionProducer start = subset(shape(len, 1), bounds, 0, 0).reshape(shape(len));
		CollectionProducer end = subset(shape(len, 1), bounds, 0, 1).reshape(shape(len));

		return a(cp(values), start.add(wave.multiply(end.subtract(start))));
	}

	/**
	 * Verifies that two concrete aggregation subclasses whose loop bodies genuinely differ —
	 * {@link CollectionSumComputation} and {@link CollectionMaxComputation} over the same data —
	 * produce distinct signatures and correct, distinct results. Each class fixes its own
	 * combining behaviour, and the concrete class is part of the aggregation signature (appended
	 * by {@link org.almostrealism.collect.computations.RepeatedProducerComputation#signature()}),
	 * so the two never share a compiled kernel through the signature-keyed instruction cache.
	 */
	@Test(timeout = 60000)
	public void aggregationFunctionsDisambiguate() {
		PackedCollection data = pack(2.0, 3.0, 4.0, 5.0);

		CollectionSumComputation sum = new CollectionSumComputation(cp(data));
		CollectionMaxComputation max = new CollectionMaxComputation(cp(data));

		String sumSignature = Signature.of(sum);
		String maxSignature = Signature.of(max);
		log("sum signature = " + sumSignature);
		log("max signature = " + maxSignature);

		assertNotNull("Aggregations should have signatures", sumSignature);
		assertNotNull("Aggregations should have signatures", maxSignature);
		assertNotEquals(sumSignature, maxSignature);

		assertEquals(14.0, sum.get().evaluate().toDouble(0));
		assertEquals(5.0, max.get().evaluate().toDouble(0));
	}

	/**
	 * Verifies that a repeated traversable computation whose behaviour is fixed by its class —
	 * {@link IndexOfMaxComputation}, which carries no caller-supplied functions — produces a
	 * stable, non-null signature that identifies the concrete class, and reproduces the same
	 * signature when rebuilt over equivalent inputs. The class identity is what keeps such a
	 * computation from sharing a cached kernel with a different computation of the same name,
	 * shape, and inputs but a different loop body.
	 */
	@Test(timeout = 60000)
	public void indexOfMaxSignatureIdentifiesClass() {
		PackedCollection data = pack(5.0, 3.0, 7.0, 10.0).reshape(2, 2).traverse(1);

		IndexOfMaxComputation indexOfMax = new IndexOfMaxComputation(cp(data));
		String signature = indexOfMax.signature();
		log("indexOfMax signature = " + signature);

		assertNotNull("indexOfMax should have a signature", signature);
		assertTrue(signature, signature.contains(IndexOfMaxComputation.class.getName()));
		assertEquals(signature, new IndexOfMaxComputation(cp(data)).signature());
	}

	/**
	 * Verifies that the gradient of a sum produces a signature. The delta of a
	 * reduction is constructed as a named {@link CollectionSumComputation} that sums the
	 * enumerated Jacobian groups, so the signature-keyed instruction cache can reuse its
	 * compiled kernel across gradient graphs containing a reduction.
	 */
	@Test(timeout = 60000)
	public void aggregationDeltaSignature() {
		PackedCollection data = new PackedCollection(shape(8)).randFill();

		CollectionProducer summed = cp(data).multiply(cp(data)).sum();
		CollectionProducer gradient = summed.delta(cp(data));

		String signature = Signature.of(gradient);
		log("delta signature = " + signature);
		assertNotNull("The gradient of a sum should have a signature", signature);
	}

	/**
	 * Verifies that a matrix-matrix product, which routes through
	 * {@link WeightedSumComputation}, produces a signature, and that weighted
	 * sums with identical operand shapes but different position rates produce
	 * distinct signatures.
	 */
	@Test(timeout = 60000)
	public void weightedSumSignatures() {
		PackedCollection a = new PackedCollection(shape(4, 8)).randFill();
		PackedCollection b = new PackedCollection(shape(8, 3)).randFill();

		CollectionProducer product = MatrixFeatures.getInstance().matmul(cp(a), cp(b));
		String matmulSignature = Signature.of(product);
		log("matmul signature = " + matmulSignature);
		assertNotNull("Matrix-matrix products should have a signature", matmulSignature);

		int m = 2;
		int n = 4;
		PackedCollection matrix = new PackedCollection(shape(1, m, n, 1));
		PackedCollection vector = new PackedCollection(shape(1, 1, n, 1));

		TraversalPolicy resultShape = shape(1, m, 1, 1);
		TraversalPolicy groupShape = shape(1, 1, n, 1);
		TraversalPolicy matrixPositions = resultShape
				.withRate(0, 1, 1).withRate(3, n, 1);
		TraversalPolicy alternatePositions = resultShape
				.withRate(0, 1, 1).withRate(3, 2 * n, 2);
		TraversalPolicy vectorPositions = resultShape.withRate(1, 1, m);

		WeightedSumComputation standard = new WeightedSumComputation(resultShape,
				matrixPositions, vectorPositions, groupShape, groupShape,
				cp(matrix), cp(vector));
		WeightedSumComputation alternate = new WeightedSumComputation(resultShape,
				alternatePositions, vectorPositions, groupShape, groupShape,
				cp(matrix), cp(vector));

		String standardSignature = Signature.of(standard);
		String alternateSignature = Signature.of(alternate);
		log("weighted sum signature = " + standardSignature);
		log("alternate weighted sum signature = " + alternateSignature);

		assertNotNull("Weighted sums should have a signature", standardSignature);
		assertNotEquals(standardSignature, alternateSignature);
	}
}
