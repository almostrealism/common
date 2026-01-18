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

/**
 * Complete training loop optimizer for neural network models.
 * <p>
 * {@code ModelOptimizer} provides a high-level API for training neural networks with
 * gradient descent. It handles the complete training loop including forward passes,
 * loss computation, backpropagation, and parameter updates.
 * </p>
 *
 * <h2>Training Loop</h2>
 * <p>
 * Each iteration of {@link #optimize(int)} performs:
 * </p>
 * <ol>
 *   <li>Forward pass through the model</li>
 *   <li>Loss computation using the configured {@link LossProvider}</li>
 *   <li>Gradient computation via automatic differentiation</li>
 *   <li>Backward pass (backpropagation)</li>
 *   <li>Parameter updates via the model's configured optimizer</li>
 * </ol>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Configurable loss functions (MSE, MAE, NLL)</li>
 *   <li>Early stopping based on loss target</li>
 *   <li>Periodic logging of training progress</li>
 *   <li>Accuracy evaluation on validation data</li>
 *   <li>Loss receptor for external monitoring</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Prepare dataset
 * List<ValueTarget<PackedCollection>> data = new ArrayList<>();
 * for (int i = 0; i < 1000; i++) {
 *     data.add(ValueTarget.of(inputs[i], targets[i]));
 * }
 *
 * // Create optimizer
 * ModelOptimizer optimizer = new ModelOptimizer(
 *     model.compile(),
 *     () -> Dataset.of(data)
 * );
 *
 * // Configure
 * optimizer.setLossFunction(new MeanSquaredError(outputShape.traverseEach()));
 * optimizer.setLogFrequency(10);    // Log every 10 epochs
 * optimizer.setLossTarget(0.001);   // Early stopping threshold
 *
 * // Train
 * optimizer.optimize(100);  // Up to 100 epochs
 *
 * // Evaluate
 * double accuracy = optimizer.accuracy((expected, output) ->
 *     argmax(expected) == argmax(output)
 * );
 *
 * System.out.println("Final loss: " + optimizer.getLoss());
 * System.out.println("Accuracy: " + (accuracy * 100) + "%");
 * }</pre>
 *
 * <h2>Classification Training</h2>
 * <pre>{@code
 * // For classification with softmax output
 * optimizer.setLossFunction(new NegativeLogLikelihood());
 *
 * // Custom accuracy check
 * double accuracy = optimizer.accuracy((target, output) -> {
 *     int expectedClass = target.argmax();
 *     int predictedClass = output.argmax();
 *     return expectedClass == predictedClass;
 * });
 * }</pre>
 *
 * @see CompiledModel
 * @see LossProvider
 * @see Dataset
 * @see AdamOptimizer
 *
 * @author Michael Murray
 */
public class ModelOptimizer implements CodeFeatures {
	private final CompiledModel model;
	private Supplier<Dataset<?>> dataset;
	private Receptor<Double> receptor;
	private int logFrequency;
	private Consumer<String> log;

	private Evaluable<PackedCollection> dloss;
	private BiFunction<PackedCollection, PackedCollection, Double> loss;
	private double averageLoss;
	private double lossTarget;
	private int totalIterations;

	/**
	 * Creates a model optimizer for an uncompiled model.
	 * <p>
	 * The model will be compiled automatically. Use {@link #setDataset(Supplier)}
	 * to set the training data before calling {@link #optimize(int)}.
	 * </p>
	 *
	 * @param model the model to train
	 */
	public ModelOptimizer(Model model) {
		this(model, null);
	}

	/**
	 * Creates a model optimizer with an uncompiled model and dataset.
	 * <p>
	 * The model will be compiled automatically.
	 * </p>
	 *
	 * @param model   the model to train
	 * @param dataset supplier for the training dataset
	 */
	public ModelOptimizer(Model model, Supplier<Dataset<?>> dataset) {
		this(model.compile(), dataset);
	}

	/**
	 * Creates a model optimizer with profiling support.
	 * <p>
	 * The model will be compiled with the specified operation profile for
	 * performance monitoring.
	 * </p>
	 *
	 * @param model   the model to train
	 * @param profile the operation profile for performance tracking
	 * @param dataset supplier for the training dataset
	 */
	public ModelOptimizer(Model model, OperationProfile profile, Supplier<Dataset<?>> dataset) {
		this(model.compile(profile), dataset);
	}

	/**
	 * Creates a model optimizer for a pre-compiled model.
	 *
	 * @param model the compiled model to train
	 */
	public ModelOptimizer(CompiledModel model) {
		this(model, null);
	}

	/**
	 * Creates a model optimizer with a compiled model and dataset.
	 * <p>
	 * The default loss function is Mean Squared Error. Use {@link #setLossFunction(LossProvider)}
	 * to change it.
	 * </p>
	 *
	 * @param model   the compiled model to train
	 * @param dataset supplier for the training dataset
	 */
	public ModelOptimizer(CompiledModel model, Supplier<Dataset<?>> dataset) {
		this.model = model;
		this.averageLoss = -1;

		setDataset(dataset);
		setLossFunction(new MeanSquaredError(model.getOutputShape().traverseEach()));
	}

	/**
	 * Sets the loss function for training.
	 * <p>
	 * The loss function determines how model predictions are compared to targets.
	 * Common choices include:
	 * </p>
	 * <ul>
	 *   <li>{@link MeanSquaredError} - For regression</li>
	 *   <li>{@link MeanAbsoluteError} - For robust regression</li>
	 *   <li>{@link NegativeLogLikelihood} - For classification</li>
	 * </ul>
	 *
	 * @param lossFunction the loss function to use
	 */
	public void setLossFunction(LossProvider lossFunction) {
		this.loss = (out, valid) -> lossFunction.loss(out, valid);
		this.dloss = lossFunction.gradient(
								cv(model.getOutputShape().traverseEach(), 0),
								cv(model.getOutputShape().traverseEach(), 1)).get();
	}

	/**
	 * Sets the training dataset supplier.
	 * <p>
	 * The supplier is called at the start of each call to {@link #optimize(int)}
	 * to obtain the dataset.
	 * </p>
	 *
	 * @param dataset supplier for the training dataset
	 */
	public void setDataset(Supplier<Dataset<?>> dataset) {
		this.dataset = dataset;
	}

	/**
	 * Sets a receptor to receive loss values during training.
	 * <p>
	 * This can be used for external monitoring or visualization.
	 * </p>
	 *
	 * @param receptor the loss receptor
	 */
	public void setReceptor(Receptor<Double> receptor) {
		this.receptor = receptor;
	}

	/**
	 * Returns the logging frequency.
	 *
	 * @return the number of iterations between log messages
	 */
	public int getLogFrequency() {
		return logFrequency;
	}

	/**
	 * Sets the logging frequency.
	 * <p>
	 * When set to a positive value, loss information is logged every N iterations.
	 * Set to 0 to disable logging.
	 * </p>
	 *
	 * @param logFrequency the number of iterations between log messages
	 */
	public void setLogFrequency(int logFrequency) {
		this.logFrequency = logFrequency;
	}

	/**
	 * Returns the custom log consumer.
	 *
	 * @return the log consumer, or null if using default logging
	 */
	public Consumer<String> getLogConsumer() {
		return log;
	}

	/**
	 * Sets a custom log consumer for training output.
	 *
	 * @param log the consumer for log messages
	 */
	public void setLogConsumer(Consumer<String> log) {
		this.log = log;
	}

	/**
	 * Sets the loss target for early stopping.
	 * <p>
	 * Training will stop early if the average loss falls below this threshold.
	 * </p>
	 *
	 * @param lossTarget the target loss value
	 */
	public void setLossTarget(double lossTarget) {
		this.lossTarget = lossTarget;
	}

	/**
	 * Returns the loss target for early stopping.
	 *
	 * @return the target loss value
	 */
	public double getLossTarget() { return lossTarget; }

	/**
	 * Returns the current average loss.
	 *
	 * @return the average loss from the last epoch, or -1 if not yet computed
	 */
	public double getLoss() {
		return averageLoss;
	}

	/**
	 * Returns the total number of training iterations completed.
	 *
	 * @return the total iteration count across all calls to {@link #optimize(int)}
	 */
	public int getTotalIterations() { return totalIterations; }

	/**
	 * Runs the training loop for the specified number of epochs.
	 * <p>
	 * For each epoch, iterates through all samples in the dataset, performing:
	 * </p>
	 * <ol>
	 *   <li>Forward pass to compute predictions</li>
	 *   <li>Loss and gradient computation</li>
	 *   <li>Backward pass (backpropagation)</li>
	 *   <li>Parameter updates</li>
	 * </ol>
	 * <p>
	 * Training stops early if:
	 * </p>
	 * <ul>
	 *   <li>Average loss falls below {@link #getLossTarget()}</li>
	 *   <li>Average loss stops improving (converged)</li>
	 * </ul>
	 *
	 * @param iterations the maximum number of epochs to run
	 * @throws RuntimeException if loss increases after the first sample
	 *                          (indicates gradient computation issues)
	 * @throws RuntimeException if no dataset samples produce valid results
	 */
	public void optimize(int iterations) {
		Dataset<?> data = dataset.get();

		for (int i = 0; i < iterations; i++) {
			boolean first = true;
			double updatedLoss;

			double totalLoss = 0.0;
			int count = 0;

			v: for (ValueTarget<?> target : data) {
				// Input
				PackedCollection input = target.getInput();
				PackedCollection[] arguments = target.getArguments();

				// Target
				PackedCollection valid = target.getExpectedOutput();

				// Forward pass and loss
				PackedCollection out = model.forward(input, arguments);
				PackedCollection grad = dloss.evaluate(out.each(), valid.each());

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

	/**
	 * Evaluates model accuracy on the dataset using a custom validator.
	 * <p>
	 * Iterates through all samples, counting how many predictions match
	 * the expected outputs according to the validator predicate.
	 * </p>
	 *
	 * @param validator a predicate that returns true if the prediction matches
	 *                  the expected output; receives (expected, predicted)
	 * @return the accuracy as a ratio from 0.0 to 1.0
	 */
	public double accuracy(BiPredicate<PackedCollection, PackedCollection> validator) {
		Dataset<?> data = dataset.get();

		double totalLoss = 0.0;
		int success = 0;
		int count = 0;

		for (ValueTarget<?> target : data) {
			PackedCollection input = target.getInput();

			PackedCollection valid = target.getExpectedOutput();
			PackedCollection out = model.forward(input);
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
