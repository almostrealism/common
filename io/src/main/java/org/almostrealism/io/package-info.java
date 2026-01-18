/*
 * Copyright 2016 Michael Murray
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

/**
 * Provides core I/O, logging, and monitoring infrastructure for the Almost Realism framework.
 *
 * <p>This package contains a hierarchical console logging system, performance metrics,
 * alerting capabilities, and lifecycle management utilities that are used throughout
 * all Almost Realism modules.</p>
 *
 * <h2>Core Components</h2>
 *
 * <h3>Logging System</h3>
 * <ul>
 *   <li>{@link org.almostrealism.io.Console} - Hierarchical logging with timestamps, listeners, and metrics</li>
 *   <li>{@link org.almostrealism.io.ConsoleFeatures} - Interface providing logging convenience methods</li>
 *   <li>{@link org.almostrealism.io.OutputFeatures} - File output utilities for directing logs to files</li>
 * </ul>
 *
 * <h3>Alerting</h3>
 * <ul>
 *   <li>{@link org.almostrealism.io.Alert} - Alert messages with severity levels (INFO, WARNING, ERROR)</li>
 *   <li>{@link org.almostrealism.io.AlertDeliveryProvider} - Interface for custom alert delivery mechanisms</li>
 * </ul>
 *
 * <h3>Performance Metrics</h3>
 * <ul>
 *   <li>{@link org.almostrealism.io.TimingMetric} - Measure and track execution times</li>
 *   <li>{@link org.almostrealism.io.DistributionMetric} - Track distributions of numeric values</li>
 *   <li>{@link org.almostrealism.io.MetricBase} - Base class for metrics</li>
 * </ul>
 *
 * <h3>Lifecycle Management</h3>
 * <ul>
 *   <li>{@link org.almostrealism.lifecycle.SuppliedValue} - Lazy initialization with validation and cleanup</li>
 *   <li>{@link org.almostrealism.lifecycle.ThreadLocalSuppliedValue} - Thread-local value management</li>
 * </ul>
 *
 * <h3>Utilities</h3>
 * <ul>
 *   <li>{@link org.almostrealism.io.Describable} - Interface for self-describing objects</li>
 *   <li>{@link org.almostrealism.io.SystemUtils} - System property and environment utilities</li>
 *   <li>{@link org.almostrealism.io.JobOutput} - Job execution output management</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Basic Logging</h3>
 * <pre>{@code
 * public class MyClass implements ConsoleFeatures {
 *     public void doSomething() {
 *         log("Starting operation");
 *         warn("This is a warning");
 *     }
 * }
 * }</pre>
 *
 * <h3>File Output</h3>
 * <pre>{@code
 * Console.root().addListener(OutputFeatures.fileOutput("/path/to/log.txt"));
 * Console.root().println("This goes to both console and file");
 * }</pre>
 *
 * <h3>Performance Timing</h3>
 * <pre>{@code
 * TimingMetric timing = Console.root().timing("myOperation");
 * timing.measure("task1", () -> performTask1());
 * timing.measure("task2", () -> performTask2());
 * Console.root().println(timing.summary());
 * }</pre>
 *
 * <h3>Lazy Initialization</h3>
 * <pre>{@code
 * SuppliedValue<ExpensiveResource> resource =
 *     new SuppliedValue<>(() -> new ExpensiveResource());
 *
 * // Resource created only when first accessed
 * ExpensiveResource r = resource.getValue();
 *
 * // Clean up when done
 * resource.destroy();
 * }</pre>
 *
 * @author Michael Murray
 */
package org.almostrealism.io;