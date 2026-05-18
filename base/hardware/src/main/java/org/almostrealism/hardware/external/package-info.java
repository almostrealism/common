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
 * External process execution and file-backed memory for out-of-process hardware operations.
 *
 * <p>This package supports running hardware computations in a separate OS process.
 * {@link org.almostrealism.hardware.external.LocalExternalMemoryProvider} provides
 * file-backed memory that survives process boundaries, and
 * {@link org.almostrealism.hardware.external.ExternalComputeContext} manages the
 * lifecycle of an external compute process.</p>
 */
package org.almostrealism.hardware.external;
