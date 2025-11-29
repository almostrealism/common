/**
 * Optimization algorithms for machine learning and evolutionary computation.
 * <p>
 * The optimize module provides both gradient-based and population-based optimization
 * algorithms for training neural networks and evolving genetic algorithms.
 * </p>
 *
 * <h2>Key Components</h2>
 *
 * <h3>Population-Based Evolution</h3>
 * <ul>
 *   <li><b>{@link org.almostrealism.optimize.PopulationOptimizer}</b> - Genetic algorithm
 *       framework with breeding, mutation, and fitness evaluation</li>
 *   <li><b>{@link org.almostrealism.optimize.HealthComputation}</b> - Fitness evaluation interface</li>
 *   <li><b>{@link org.almostrealism.optimize.HealthScore}</b> - Fitness score representation</li>
 *   <li><b>{@link org.almostrealism.optimize.HealthScoring}</b> - Statistics aggregation</li>
 * </ul>
 *
 * <h3>Gradient-Based Optimization</h3>
 * <ul>
 *   <li><b>{@link org.almostrealism.optimize.AdamOptimizer}</b> - Adaptive learning rate optimizer</li>
 *   <li><b>{@link org.almostrealism.optimize.ModelOptimizer}</b> - Complete training loop for models</li>
 *   <li><b>Loss Functions</b> - MSE, MAE, and NLL implementations</li>
 * </ul>
 *
 * <h3>Dataset Management</h3>
 * <ul>
 *   <li><b>Dataset</b> - Iterable collection with train/validation splits</li>
 *   <li><b>ValueTarget</b> - Input-output pairs for supervised learning</li>
 *   <li><b>FunctionalDataset</b> - On-the-fly data generation</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Population-Based Evolution</h3>
 * <pre>{@code
 * PopulationOptimizer<Genome, Temporal, Output, Score> optimizer =
 *     new PopulationOptimizer<>(
 *         healthComputationSupplier,
 *         populationConstructor,
 *         breederSupplier,
 *         genomeGeneratorSupplier
 *     );
 *
 * // Configure
 * PopulationOptimizer.popSize = 100;
 * PopulationOptimizer.THREADS = 8;
 *
 * // Run evolution
 * for (int gen = 0; gen < 50; gen++) {
 *     optimizer.iterate();
 *     System.out.println("Generation " + gen +
 *         ": avg=" + optimizer.getAverageScore());
 * }
 * }</pre>
 *
 * <h3>Model Training</h3>
 * <pre>{@code
 * ModelOptimizer optimizer = new ModelOptimizer(
 *     model.compile(),
 *     () -> Dataset.of(trainingData)
 * );
 *
 * optimizer.setLossFunction(new MeanSquaredError(outputShape.traverseEach()));
 * optimizer.setLossTarget(0.001);  // Early stopping
 * optimizer.optimize(100);         // Up to 100 epochs
 *
 * double accuracy = optimizer.accuracy(accuracyPredicate);
 * }</pre>
 *
 * <h3>Loss Functions</h3>
 * <pre>{@code
 * // Mean Squared Error for regression
 * LossProvider mse = new MeanSquaredError(traverseEach());
 *
 * // Mean Absolute Error for robust regression
 * LossProvider mae = new MeanAbsoluteError(traverseEach());
 *
 * // Negative Log Likelihood for classification
 * LossProvider nll = new NegativeLogLikelihood(traverseEach());
 * }</pre>
 *
 * <h2>Integration with Other Modules</h2>
 * <ul>
 *   <li><b>ar-heredity</b> - Genome, Chromosome, Gene for evolutionary algorithms</li>
 *   <li><b>ar-graph</b> - Model and CompiledModel for neural networks</li>
 *   <li><b>ar-collect</b> - PackedCollection for tensor data</li>
 *   <li><b>ar-hardware</b> - GPU acceleration for computations</li>
 * </ul>
 *
 * @see org.almostrealism.optimize.PopulationOptimizer
 * @see org.almostrealism.optimize.ModelOptimizer
 * @see org.almostrealism.optimize.AdamOptimizer
 * @see org.almostrealism.optimize.HealthComputation
 */
package org.almostrealism.optimize;
