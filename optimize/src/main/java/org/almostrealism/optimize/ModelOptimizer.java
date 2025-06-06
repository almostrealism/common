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

package org.almostrealism.optimize;

import io.almostrealism.profile.OperationProfile;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.io.Console;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;

import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ModelOptimizer implements CodeFeatures {
	private CompiledModel model;
	private Supplier<Dataset<?>> dataset;
	private Receptor<Double> receptor;
	private int logFrequency;
	private Consumer<String> log;

	private Evaluable<PackedCollection<?>> dloss;
	private BiFunction<PackedCollection<?>, PackedCollection<?>, Double> loss;
	private double averageLoss;
	private double lossTarget;
	private int totalIterations;

	public ModelOptimizer(Model model) {
		this(model, null);
	}

	public ModelOptimizer(Model model, Supplier<Dataset<?>> dataset) {
		this(model.compile(), dataset);
	}

	public ModelOptimizer(Model model, OperationProfile profile, Supplier<Dataset<?>> dataset) {
		this(model.compile(profile), dataset);
	}

	public ModelOptimizer(CompiledModel model) {
		this(model, null);
	}

	public ModelOptimizer(CompiledModel model, Supplier<Dataset<?>> dataset) {
		this.model = model;
		this.averageLoss = -1;

		setDataset(dataset);
		setLossFunction(new MeanSquaredError(model.getOutputShape().traverseEach()));
	}

	public void setLossFunction(LossProvider lossFunction) {
		this.loss = (out, valid) -> lossFunction.loss(out, valid);
		this.dloss = lossFunction.gradient(
								cv(model.getOutputShape().traverseEach(), 0),
								cv(model.getOutputShape().traverseEach(), 1)).get();
	}

	public void setDataset(Supplier<Dataset<?>> dataset) {
		this.dataset = dataset;
	}

	public void setReceptor(Receptor<Double> receptor) {
		this.receptor = receptor;
	}

	public int getLogFrequency() {
		return logFrequency;
	}

	public void setLogFrequency(int logFrequency) {
		this.logFrequency = logFrequency;
	}

	public Consumer<String> getLogConsumer() {
		return log;
	}

	public void setLogConsumer(Consumer<String> log) {
		this.log = log;
	}

	public void setLossTarget(double lossTarget) {
		this.lossTarget = lossTarget;
	}

	public double getLossTarget() { return lossTarget; }

	public double getLoss() {
		return averageLoss;
	}

	public int getTotalIterations() { return totalIterations; }

	public void optimize(int iterations) {
		Dataset<?> data = dataset.get();

		for (int i = 0; i < iterations; i++) {
			boolean first = true;
			double updatedLoss;

			double totalLoss = 0.0;
			int count = 0;

			v: for (ValueTarget<?> target : data) {
				// Input
				PackedCollection<?> input = target.getInput();
				PackedCollection<?>[] arguments = target.getArguments();

				// Target
				PackedCollection<?> valid = target.getExpectedOutput();

				// Forward pass and loss
				PackedCollection<?> out = model.forward(input, arguments);
				PackedCollection<?> grad = dloss.evaluate(out.each(), valid.each());

				double ls = loss.apply(out.each(), valid.each());
				if (Double.isNaN(ls)) continue v;

				if (i == 0 && count == 0 && logIteration(totalIterations + 1))
					log("loss = " + ls);

				totalLoss += ls;
				count++;

				if (receptor != null)
					receptor.push(() -> args -> ls).get().run();

				model.backward(grad);

				if (first) {
					out = model.forward(input, arguments);
					updatedLoss = loss.apply(out, valid);

					if ((ls - updatedLoss) < 0.0) {
						throw new RuntimeException("Loss increased from " + ls + " to " + updatedLoss);
					}

					first = false;
				}
			}

			if (count == 0) {
				throw new RuntimeException("No members of the dataset produced valid results");
			}

			totalIterations++;

			double previousLoss = averageLoss;
			averageLoss = totalLoss / count;

			if (logIteration(totalIterations))
				log("Average Loss = " + averageLoss);

			if (averageLoss < lossTarget || averageLoss == previousLoss) {
				return;
			}
		}
	}

	protected boolean logIteration(int iteration) {
		return logFrequency > 0 && iteration % logFrequency == 0;
	}

	public double accuracy(BiPredicate<PackedCollection<?>, PackedCollection<?>> validator) {
		Dataset<?> data = dataset.get();

		double totalLoss = 0.0;
		int success = 0;
		int count = 0;

		for (ValueTarget<?> target : data) {
			PackedCollection<?> input = target.getInput();

			PackedCollection<?> valid = target.getExpectedOutput();
			PackedCollection<?> out = model.forward(input);
			double ls = loss.apply(out, valid);
			totalLoss += ls;
			count++;

			if (validator.test(target.getExpectedOutput(), out))
				success++;

			if (receptor != null)
				receptor.push(() -> args -> ls).get().run();
		}

		return success / (double) count;
	}

	@Override
	public void log(String message) {
		if (getLogConsumer() == null) {
			CodeFeatures.super.log(message);
		} else {
			getLogConsumer().accept(message);
		}
	}

	@Override
	public Console console() {
		return HealthCallable.console;
	}
}
