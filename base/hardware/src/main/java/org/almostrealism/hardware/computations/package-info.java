/*
 * Copyright 2025 Michael Murray
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

/**
 * Hardware-accelerated computation building blocks and execution wrappers.
 *
 * <p>This package contains the core computation types used to build and execute
 * hardware-accelerated operations. Key classes include:</p>
 * <ul>
 *   <li>{@link org.almostrealism.hardware.computations.HardwareEvaluable} - evaluable that
 *       manages context-specific kernel dispatch and async execution</li>
 *   <li>{@link org.almostrealism.hardware.computations.Loop} - compiled loop over a computation</li>
 *   <li>{@link org.almostrealism.hardware.computations.Periodic} - counter-based periodic execution</li>
 *   <li>{@link org.almostrealism.hardware.computations.MetricComputation} - runtime metric logging</li>
 *   <li>{@link org.almostrealism.hardware.computations.DelegatedProducer} - pass-through producers</li>
 * </ul>
 */
package org.almostrealism.hardware.computations;
