/*
 * Copyright 2026 Michael Murray
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
 * CUDA backend for NVIDIA GPUs.
 *
 * <p>{@link org.almostrealism.hardware.cuda.CU} is the JNI binding to the CUDA Driver API
 * and NVRTC, backed by {@code src/main/cpp/CUDA.cpp}. Native objects are held through
 * typed wrappers: {@link org.almostrealism.hardware.cuda.CUDevice},
 * {@link org.almostrealism.hardware.cuda.CUContext},
 * {@link org.almostrealism.hardware.cuda.CUStream},
 * {@link org.almostrealism.hardware.cuda.CUEvent},
 * {@link org.almostrealism.hardware.cuda.CUModule},
 * {@link org.almostrealism.hardware.cuda.CUFunction} and
 * {@link org.almostrealism.hardware.cuda.CUDeviceBuffer}.</p>
 *
 * <h2>Design decisions</h2>
 *
 * <ul>
 *   <li><b>Hand-written JNI bridge</b> over the Driver API and NVRTC, like the Metal bridge,
 *       rather than a third-party binding: no additional build dependency and the same
 *       maintenance model as the Metal backend.</li>
 *   <li><b>Prebuilt native library.</b> {@code libARCUDA} is committed under
 *       {@code src/main/resources}, with {@code src/main/cpp/compile-cuda.sh} as the source of
 *       truth for building it, so neither the Maven build nor every CI machine needs a CUDA
 *       toolkit.</li>
 *   <li><b>Scalar argument offsets and sizes.</b> Unlike Metal, which passes per-argument
 *       offsets and sizes in extra buffers written with {@code setBytes}, generated kernels use
 *       the OpenCL-style layout: each argument becomes a pointer plus scalar offset and size
 *       parameters ({@link org.almostrealism.hardware.cuda.CudaLanguageOperations} disables
 *       argument detail reads). This fits {@code cuLaunchKernel}'s parameter array and needs no
 *       per-dispatch allocation.</li>
 *   <li><b>Driver selection.</b> The driver token is {@code cuda}. The {@code *} and
 *       {@code gpu} selections do not yet resolve to CUDA; when they do, resolution should be
 *       availability-aware rather than based on operating-system checks.</li>
 *   <li><b>Integrated and discrete devices.</b> The backend is designed for both unified-memory
 *       (integrated) and discrete NVIDIA GPUs, so it does not silently depend on host access to
 *       device memory. Sharing CUDA memory with host kernels is off by default (see
 *       {@link org.almostrealism.hardware.cuda.CudaDataContext}).</li>
 *   <li><b>No backend {@code instanceof} checks.</b> Code that needs a backend-specific
 *       capability (for example, whether native code can address a provider's memory directly)
 *       should ask the memory provider or data context through the shared abstraction, not test
 *       for a concrete CUDA, Metal or OpenCL class.</li>
 * </ul>
 */
package org.almostrealism.hardware.cuda;
