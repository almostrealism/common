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

/**
 * Cross-module testing utilities and code-quality enforcement tools.
 *
 * <p>This package provides the core testing infrastructure for the Almost Realism
 * framework, along with static analysis tools that enforce project-wide code policies.</p>
 *
 * <h2>Testing Framework</h2>
 * <ul>
 *   <li>{@link org.almostrealism.util.TestFeatures} - Primary testing interface
 *       combining assertion helpers, kernel test utilities, and performance metrics</li>
 *   <li>{@link org.almostrealism.util.TestSettings} - Shared test configuration
 *       flags driven by environment variables</li>
 *   <li>{@link org.almostrealism.util.TestDepthRule} - JUnit {@code MethodRule}
 *       that skips tests whose {@code @TestDepth} exceeds the current depth level</li>
 *   <li>{@link org.almostrealism.util.Chart} - Console-based time-series chart
 *       for tracking numeric values during long-running tests</li>
 *   <li>{@link org.almostrealism.util.RayFieldFactory} - Factory for generating
 *       ray fields within a bounding volume for ray-tracing tests</li>
 * </ul>
 *
 * <h2>Code Quality Tools</h2>
 * <ul>
 *   <li>{@link org.almostrealism.util.CodePolicyViolationDetector} - Enforces GPU
 *       memory model rules, catching {@code setMem} loops and illegal array copies</li>
 *   <li>{@link org.almostrealism.util.DuplicateCodeDetector} - Flags identical code
 *       blocks appearing in multiple files</li>
 *   <li>{@link org.almostrealism.util.TestTimeoutEnforcementScanner} - Verifies
 *       that every {@code @Test} annotation includes a {@code timeout} parameter</li>
 * </ul>
 *
 * <h2>Alert Delivery</h2>
 * <ul>
 *   <li>{@link org.almostrealism.util.SignalWireDeliveryProvider} - SMS alert
 *       delivery via the SignalWire REST API</li>
 * </ul>
 */
package org.almostrealism.util;
