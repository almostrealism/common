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
 * Hardware compute requirements, process lifecycle, and execution context abstractions.
 *
 * <p>This package defines the compute-level contracts that sit above the raw memory model
 * but below the full code generation pipeline. Key types include:</p>
 *
 * <ul>
 *   <li>{@link io.almostrealism.compute.ComputeRequirement} — declares hardware affinity
 *       (CPU, GPU, FPGA, OpenCL, Metal, etc.) for a computation</li>
 *   <li>{@link io.almostrealism.compute.PhysicalScope} — distinguishes GLOBAL (cross-workgroup)
 *       from LOCAL (within-workgroup) memory scopes</li>
 *   <li>{@link io.almostrealism.compute.Process} — the executable unit produced by optimizing
 *       a computation graph</li>
 *   <li>{@link io.almostrealism.compute.ComputableProcessContext} — the context in which
 *       a process is executed</li>
 * </ul>
 */
package io.almostrealism.compute;
