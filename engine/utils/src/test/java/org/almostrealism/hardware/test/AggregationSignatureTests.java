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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.uml.Signature;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.algebra.computations.WeightedSumComputation;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionMaxComputation;
import org.almostrealism.collect.computations.CollectionMinComputation;
import org.almostrealism.collect.computations.CollectionSumComputation;
import org.almostrealism.collect.computations.ConstantRepeatedDeltaComputation;
import org.almostrealism.collect.computations.IndexOfMaxComputation;
import org.almostrealism.collect.computations.IndexOfMinComputation;
import org.almostrealism.collect.computations.RepeatedProducerComputationAdapter;
import org.almostrealism.collect.computations.TraversableDeltaComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.function.Function;
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
	 * Verifies that {@link CollectionSumComputation#withReplaceLoop(boolean)} returns its own
	 * concrete type with the flag toggled — not a raw aggregation — and leaves the computed
	 * result unchanged. This guards the behaviour the subclass model fixed: previously, toggling
	 * the flag on a sum returned a raw {@code AggregatedProducerComputation}, silently dropping
	 * the sum's type and its {@code deltaDependent} override. The matching-setting call must
	 * return the same instance rather than rebuild.
	 */
	@Test(timeout = 60000)
	public void sumWithReplaceLoopRebuildsSumType() {
		PackedCollection data = pack(2.0, 3.0, 4.0, 5.0);

		CollectionSumComputation sum = new CollectionSumComputation(cp(data));
		assertTrue("Sum is constructed with loop replacement enabled", sum.isReplaceLoop());
		assertTrue("Matching setting returns the same instance", sum == sum.withReplaceLoop(true));

		CollectionSumComputation toggled = sum.withReplaceLoop(false);
		assertTrue("Toggling rebuilds a CollectionSumComputation",
				toggled.getClass() == CollectionSumComputation.class);
		assertFalse("Loop replacement is now disabled", toggled.isReplaceLoop());
		assertEquals(14.0, toggled.get().evaluate().toDouble(0));
	}

	/**
	 * Verifies that {@link CollectionMaxComputation#withReplaceLoop(boolean)} returns the same
	 * instance when the setting already matches and rejects a change otherwise. A max reduction
	 * is constructed without loop replacement and has no reason to toggle it, so requesting a
	 * different setting raises {@link UnsupportedOperationException} (matching the weighted-sum
	 * precedent) rather than silently rebuilding a differently-behaving kernel.
	 */
	@Test(timeout = 60000)
	public void maxWithReplaceLoopRejectsChange() {
		PackedCollection data = pack(2.0, 3.0, 4.0, 5.0);

		CollectionMaxComputation max = new CollectionMaxComputation(cp(data));
		assertFalse("Max is constructed without loop replacement", max.isReplaceLoop());
		assertTrue("Matching setting returns the same instance", max == max.withReplaceLoop(false));

		boolean rejected = false;
		try {
			max.withReplaceLoop(true);
		} catch (UnsupportedOperationException e) {
			rejected = true;
		}
		assertTrue("Changing the loop replacement setting on a max is unsupported", rejected);
		assertEquals(5.0, max.get().evaluate().toDouble(0));
	}

	/**
	 * Verifies that {@link CollectionMinComputation#withReplaceLoop(boolean)} keeps its own
	 * concrete return type, returns the same instance when the setting already matches, and
	 * rejects a change, exactly as the maximum does.
	 */
	@Test(timeout = 60000)
	public void minWithReplaceLoopRejectsChange() {
		PackedCollection data = pack(4.0, 3.0, 2.0, 5.0);

		CollectionMinComputation min = new CollectionMinComputation(cp(data));
		assertFalse("Min is constructed without loop replacement", min.isReplaceLoop());
		CollectionMinComputation same = min.withReplaceLoop(false);
		assertTrue("Matching setting returns the same instance", min == same);

		boolean rejected = false;
		try {
			min.withReplaceLoop(true);
		} catch (UnsupportedOperationException e) {
			rejected = true;
		}
		assertTrue("Changing the loop replacement setting on a min is unsupported", rejected);
		assertEquals(2.0, min.get().evaluate().toDouble(0));
	}

	/**
	 * Verifies that the minimum and maximum reductions, and the index-of-minimum and
	 * index-of-maximum searches, never share a signature over the same data. Each pair differs
	 * only in the direction of its comparison, so a shared signature would let the
	 * signature-keyed instruction cache run one in place of the other.
	 */
	@Test(timeout = 60000)
	public void minAndMaxSignaturesDisambiguate() {
		PackedCollection data = pack(5.0, 3.0, 7.0, 10.0).reshape(2, 2).traverse(1);

		CollectionMinComputation min = new CollectionMinComputation(cp(data));
		CollectionMaxComputation max = new CollectionMaxComputation(cp(data));
		assertNotNull("min should have a signature", min.signature());
		assertNotEquals(max.signature(), min.signature());
		assertEquals(min.signature(), new CollectionMinComputation(cp(data)).signature());

		IndexOfMinComputation indexOfMin = new IndexOfMinComputation(cp(data));
		IndexOfMaxComputation indexOfMax = new IndexOfMaxComputation(cp(data));
		String signature = indexOfMin.signature();
		assertNotNull("indexOfMin should have a signature", signature);
		assertTrue(signature, signature.contains(IndexOfMinComputation.class.getName()));
		assertNotEquals(indexOfMax.signature(), signature);

		PackedCollection minimum = min.get().evaluate();
		assertEquals(3.0, minimum.toDouble(0));
		assertEquals(7.0, minimum.toDouble(1));

		PackedCollection index = indexOfMin.get().evaluate();
		assertEquals(1.0, index.toDouble(0));
		assertEquals(0.0, index.toDouble(1));
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

	/**
	 * Verifies that a {@link RepeatedProducerComputationAdapter} includes the adapted expression in
	 * its signature. The adapter's loop body is the expression it wraps, but its name, inputs,
	 * output shape, and concrete class are identical for every adapter over the same inputs, so
	 * without the expression two adapters that wrap {@code add(a, b)} and {@code multiply(a, b)}
	 * would share a signature and the signature-keyed instruction cache could run one in place of
	 * the other. The adapters are constructed directly over identical inputs so the expression is
	 * the only state that differs. Rebuilding the identical adapter reproduces the identical
	 * signature.
	 */
	@Test(timeout = 60000)
	public void repeatedAdapterExpressionDisambiguates() {
		PackedCollection a = new PackedCollection(shape(4)).randFill();
		PackedCollection b = new PackedCollection(shape(4)).randFill();

		RepeatedProducerComputationAdapter addAdapter = new RepeatedProducerComputationAdapter(
				shape(4), (TraversableExpression) add(cp(a), cp(b)), cp(a), cp(b));
		RepeatedProducerComputationAdapter multiplyAdapter = new RepeatedProducerComputationAdapter(
				shape(4), (TraversableExpression) multiply(cp(a), cp(b)), cp(a), cp(b));

		String addSignature = addAdapter.signature();
		String multiplySignature = multiplyAdapter.signature();
		log("repeated add signature = " + addSignature);
		log("repeated multiply signature = " + multiplySignature);

		assertNotNull("Repeated adapter should have a signature", addSignature);
		assertNotEquals(addSignature, multiplySignature);

		assertEquals(addSignature, new RepeatedProducerComputationAdapter(
				shape(4), (TraversableExpression) add(cp(a), cp(b)), cp(a), cp(b)).signature());
	}

	/**
	 * Verifies that a {@link ConstantRepeatedDeltaComputation} includes the source being
	 * differentiated and the target it is differentiated with respect to in its signature. The
	 * delta's loop body differentiates the source's iteration step with respect to the target, yet
	 * the inherited signature sees neither (the source's behaviour is not an input, and only the
	 * target <em>shape</em> reaches the signature through the appended output shape). Without these
	 * two components the gradients of an index-of-maximum and an index-of-minimum over the same
	 * data — or of one source with respect to two distinct same-shape targets — would share a
	 * signature. Rebuilding the identical delta reproduces the identical signature.
	 */
	@Test(timeout = 60000)
	public void constantRepeatedDeltaSourceAndTargetDisambiguate() {
		PackedCollection data = pack(5.0, 3.0, 7.0, 10.0).reshape(2, 2).traverse(1);
		PackedCollection target = new PackedCollection(shape(4)).randFill();

		ConstantRepeatedDeltaComputation maxDelta =
				new ConstantRepeatedDeltaComputation(new IndexOfMaxComputation(cp(data)), cp(target));
		ConstantRepeatedDeltaComputation minDelta =
				new ConstantRepeatedDeltaComputation(new IndexOfMinComputation(cp(data)), cp(target));

		String maxSignature = maxDelta.signature();
		log("indexOfMax delta signature = " + maxSignature);
		assertNotNull("Delta should have a signature", maxSignature);
		assertNotEquals(maxSignature, minDelta.signature());

		assertEquals(maxSignature,
				new ConstantRepeatedDeltaComputation(new IndexOfMaxComputation(cp(data)), cp(target)).signature());

		PackedCollection targetPair = new PackedCollection(shape(2, 4)).randFill();
		ConstantRepeatedDeltaComputation firstTargetDelta = new ConstantRepeatedDeltaComputation(
				new IndexOfMaxComputation(cp(data)), cp(targetPair.range(shape(4), 0)));
		ConstantRepeatedDeltaComputation secondTargetDelta = new ConstantRepeatedDeltaComputation(
				new IndexOfMaxComputation(cp(data)), cp(targetPair.range(shape(4), 4)));
		assertNotEquals(firstTargetDelta.signature(), secondTargetDelta.signature());
	}

	/**
	 * Verifies that a {@link TraversableDeltaComputation} includes both the expression it
	 * differentiates and the target it differentiates with respect to in its signature. The
	 * expression is an arbitrary function that cannot sign itself, and the target is not an input,
	 * so neither reaches the inherited signature (only the target shape does). This covers the two
	 * candidate collisions behind the delta's instruction-cache gap: the deltas of one expression
	 * with respect to two distinct same-shape targets (as produced by
	 * {@code TraversableExpressionComputation.deltaMatrix}), and the deltas of two different
	 * aggregations over the same inputs (as produced by the non-transitive fallback of
	 * {@code AggregatedProducerComputation.deltaMatrix}, which names every such delta
	 * {@code "delta"}). Rebuilding the identical delta reproduces the identical signature.
	 */
	@Test(timeout = 60000)
	public void traversableDeltaExpressionAndTargetDisambiguate() {
		int n = 4;
		PackedCollection source = new PackedCollection(shape(n)).randFill();
		PackedCollection targetPair = new PackedCollection(shape(2, n)).randFill();
		PackedCollection targetA = targetPair.range(shape(n), 0);
		PackedCollection targetB = targetPair.range(shape(n), n);

		Function<TraversableExpression[], CollectionExpression> expression =
				args -> CollectionExpression.create(shape(n), idx -> args[1].getValueAt(idx));
		String expressionSignature = "identity";

		TraversableDeltaComputation deltaTargetA = TraversableDeltaComputation.create(
				"\u03B4x", shape(n), shape(n), expression, expressionSignature, cp(targetA), cp(source));
		TraversableDeltaComputation deltaTargetB = TraversableDeltaComputation.create(
				"\u03B4x", shape(n), shape(n), expression, expressionSignature, cp(targetB), cp(source));

		String signatureA = deltaTargetA.signature();
		log("delta target A signature = " + signatureA);
		assertNotNull("Delta should have a signature", signatureA);
		assertNotEquals(signatureA, deltaTargetB.signature());

		assertEquals(signatureA, TraversableDeltaComputation.create(
				"\u03B4x", shape(n), shape(n), expression, expressionSignature,
				cp(targetA), cp(source)).signature());

		CollectionSumComputation sumSource = new CollectionSumComputation(cp(source));
		CollectionMaxComputation maxSource = new CollectionMaxComputation(cp(source));

		TraversableDeltaComputation sumDelta = TraversableDeltaComputation.create(
				"delta", shape(1), shape(n),
				args -> CollectionExpression.create(shape(1), sumSource::getValueAt),
				sumSource.signature(), cp(targetA), cp(source));
		TraversableDeltaComputation maxDelta = TraversableDeltaComputation.create(
				"delta", shape(1), shape(n),
				args -> CollectionExpression.create(shape(1), maxSource::getValueAt),
				maxSource.signature(), cp(targetA), cp(source));

		String sumDeltaSignature = sumDelta.signature();
		log("sum delta signature = " + sumDeltaSignature);
		assertNotNull("Delta of a sum should have a signature", sumDeltaSignature);
		assertNotEquals(sumDeltaSignature, maxDelta.signature());
	}
}
