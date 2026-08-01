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
 * Instruction set compilation and scope compilation infrastructure for hardware-accelerated operations.
 *
 * <p>This package provides the compiler infrastructure that translates high-level
 * {@link io.almostrealism.scope.Scope} trees into native instruction sets ready
 * for execution on GPU and CPU backends. Key responsibilities include argument
 * mapping, scope compilation, and position-keyed process tree management.</p>
 */
package org.almostrealism.hardware.instructions;
