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
 * Compute context management and context-specific value infrastructure.
 *
 * <p>This package provides abstractions for managing compute contexts that span
 * multiple hardware backends. {@link org.almostrealism.hardware.ctx.ContextSpecific}
 * provides per-context caching of evaluable instances, while
 * {@link org.almostrealism.hardware.ctx.HardwareDataContext} defines the contract
 * for data contexts that allocate and manage hardware memory.</p>
 */
package org.almostrealism.hardware.ctx;
