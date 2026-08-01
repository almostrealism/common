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
 * Profiling and metadata infrastructure for computation operations.
 *
 * <p>This package provides types for attaching human-readable descriptions and
 * performance profiling data to operations. Key types:</p>
 *
 * <ul>
 *   <li>{@link io.almostrealism.profile.OperationMetadata} — descriptive metadata about
 *       an operation (function name, display name, short description, source info)</li>
 *   <li>{@link io.almostrealism.profile.OperationProfile} — accumulates timing information
 *       for a named operation across multiple invocations</li>
 *   <li>{@link io.almostrealism.profile.OperationProfileNode} — a tree node in a profiling
 *       hierarchy for nested operation timing</li>
 *   <li>{@link io.almostrealism.profile.OperationSource} — tracks the source context
 *       (class, method) from which an operation was created</li>
 *   <li>{@link io.almostrealism.profile.OperationWithInfo} — wraps an operation with
 *       associated metadata and profile information</li>
 * </ul>
 */
package io.almostrealism.profile;
