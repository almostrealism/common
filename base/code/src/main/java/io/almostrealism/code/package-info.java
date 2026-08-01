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
 * Core computation model, memory management, and code generation contracts.
 *
 * <p>This package defines the foundational interfaces and abstractions that govern
 * how computations are represented, compiled, and executed across hardware backends.
 * Key concepts include:</p>
 *
 * <ul>
 *   <li>{@link io.almostrealism.code.Computation} — the root interface for all compilable computations</li>
 *   <li>{@link io.almostrealism.code.Computer} — the engine that compiles computations to native code</li>
 *   <li>{@link io.almostrealism.code.DataContext} — manages device memory and compute contexts</li>
 *   <li>{@link io.almostrealism.code.Memory} — an opaque handle to device-resident memory</li>
 *   <li>{@link io.almostrealism.code.MemoryProvider} — allocates and transfers memory</li>
 *   <li>{@link io.almostrealism.code.Execution} — a compiled computation ready for dispatch</li>
 *   <li>{@link io.almostrealism.code.InstructionSet} — a compiled program containing named functions</li>
 *   <li>{@link io.almostrealism.code.Precision} — floating-point precision (FP16/FP32/FP64)</li>
 *   <li>{@link io.almostrealism.code.ExpressionFeatures} — factory methods for building expression trees</li>
 *   <li>{@link io.almostrealism.code.ScopeLifecycle} — lifecycle hooks for scope compilation</li>
 * </ul>
 */
package io.almostrealism.code;
