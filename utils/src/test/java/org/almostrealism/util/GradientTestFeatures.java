/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.util;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;

import java.util.function.BiConsumer;
import java.util.function.IntFunction;

public interface GradientTestFeatures extends CodeFeatures {

	default void runTest(String name, int dim,
						 IntFunction<PackedCollection<?>> inputGenerator,
						 Factor<PackedCollection<?>> operation,
						 BiConsumer<PackedCollection<?>, PackedCollection<?>> validate) {
		runTest(name, dim, inputGenerator, operation, validate, true, true);
	}

	default void runTest(String name, int dim,
						 IntFunction<PackedCollection<?>> inputGenerator,
						 Factor<PackedCollection<?>> operation,
						 BiConsumer<PackedCollection<?>, PackedCollection<?>> validate,
						 boolean fixed, boolean variable) {
		if (fixed) {
			// TODO  Should use kernelTest
			log("Validating fixed input...");
			PackedCollection<?> fixedInput = inputGenerator.apply(1);
			fixedInput = fixedInput.reshape(fixedInput.getShape().item());

			CollectionProducer<PackedCollection<?>> p = cp(fixedInput);

			Producer<PackedCollection<?>> c = operation.getResultant(p);
			PackedCollection<?> out = c.get().evaluate();
			validate.accept(
					fixedInput.reshape(fixedInput.getShape().prependDimension(1)),
					out.reshape(out.getShape().prependDimension(1)));
		}

		if (variable) {
			log("Validating variable input...");
			PackedCollection<?> variableInput = inputGenerator.apply(4);
			CollectionProducer<PackedCollection<?>> x = x(dim);

			Producer<PackedCollection<?>> c = operation.getResultant(x);
			PackedCollection<?> out = c.get().evaluate(variableInput);
			validate.accept(variableInput, out);
		}
	}

	default Producer<PackedCollection<?>> applyGradient(CollectionProducer<?> delta,
														CollectionProducer<?> gradient) {
		CollectionFeatures cf = CollectionFeatures.getInstance();
		int outSize = cf.shape(gradient).getTotalSize();
		int inSize = cf.shape(delta).getTotalSize() / outSize;
		return delta.reshape(outSize, inSize)
				.traverse(1)
				.multiply(gradient.reshape(outSize).traverse(1).repeat(inSize))
				.traverse(0)
				.enumerate(1, 1)
				.sum(1)
				.reshape(cf.shape(inSize))
				.each();
	}

	default PackedCollection<?> normBackwards(PackedCollection<?> xGroup,
											  PackedCollection<?> gradient,
											  PackedCollection<?> weights,
											  PackedCollection<?> bias) {
		double eps = Hardware.getLocalHardware().epsilon();
		int groupSize = xGroup.getShape().getTotalSize();

		double muG = xGroup.doubleStream().sum() / groupSize;
		double varG = variance(cp(xGroup)).evaluate().toDouble();
		double stdG = Math.sqrt(varG + eps);

		PackedCollection<?> xHatGroup = cp(xGroup).subtract(c(muG)).divide(c(stdG)).evaluate();

		PackedCollection<?> dLdBeta = gradient;
		PackedCollection<?> dLdGamma = cp(gradient).multiply(cp(xHatGroup)).evaluate();

		PackedCollection<?> dLdHatXGroup;

		if (weights == null) {
			dLdHatXGroup = gradient;
		} else {
			// dLdHatXGroup = cp(gradient).multiply(cp(weights)).evaluate();
			dLdHatXGroup = new PackedCollection<>(gradient.getShape())
					.fill(pos -> gradient.valueAt(pos) * weights.valueAt(pos));
		}

		double dLdHatXGroupMean = dLdHatXGroup.doubleStream().sum() / groupSize;
		PackedCollection<?> dLdHatXGroupXHatGroup = cp(dLdHatXGroup).multiply(cp(xHatGroup)).evaluate();

		double dLdHatXGroupXHatGroupMean = dLdHatXGroupXHatGroup.doubleStream().sum() / groupSize;

		return dlDxGroup(
					dLdHatXGroup, dLdHatXGroupMean,
					xHatGroup, dLdHatXGroupXHatGroupMean)
				.replace(v -> v / stdG);
	}

	default PackedCollection<?> dlDxGroup(int c, PackedCollection<?> o, PackedCollection<?> g) {
		double eps = 1e-5;
		double muG = o.doubleStream().sum() / c;
		double varG = variance(cp(o)).evaluate().toDouble();
		double stdG = Math.sqrt(varG + eps);

		PackedCollection<?> normalized =
				cp(o).subtract(c(muG))
						.divide(c(stdG))
						.evaluate();

		double gradientMean = g.doubleStream().sum() / c;
		PackedCollection<?> gradientByInput = cp(g).multiply(cp(normalized)).evaluate();

		double gradientByInputMean = gradientByInput.doubleStream().sum() / c;
		return dlDxGroup(
				g, gradientMean, normalized, gradientByInputMean);
	}

	// TODO  Make private
	default PackedCollection<?> dlDxGroup(PackedCollection<?> dLdHatXGroup,
										  double dLdHatXGroupMean,
										  PackedCollection<?> xHatGroup,
										  double dLdHatXGroupXHatGroupMean) {
		return new PackedCollection<>(dLdHatXGroup.getShape())
				.fill(pos -> dLdHatXGroup.valueAt(pos) - dLdHatXGroupMean
						- xHatGroup.valueAt(pos) * dLdHatXGroupXHatGroupMean);

//		return cp(dLdHatXGroup)
//				.subtract(c(dLdHatXGroupMean))
//				.subtract(cp(xHatGroup).multiply(c(dLdHatXGroupXHatGroupMean)))
//				.evaluate();
	}
}
