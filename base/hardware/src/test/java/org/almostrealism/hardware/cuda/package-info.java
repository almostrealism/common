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
 * Tests for the CUDA backend wrappers that can be exercised without an accelerator.
 *
 * <p>These tests live in the {@code cuda} package (rather than the module's
 * {@code hardware.test} package) so they can reach the package-private wrapper
 * constructors and drive the host-side validation that {@link org.almostrealism.hardware.cuda.CUContext}
 * and {@link org.almostrealism.hardware.cuda.CUDeviceBuffer} perform before crossing into JNI.
 * They load no native library and need no CUDA device, like the other lifecycle tests in this
 * module.</p>
 */
package org.almostrealism.hardware.cuda;
