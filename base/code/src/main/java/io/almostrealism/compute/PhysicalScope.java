/*
 * Copyright 2021 Michael Murray
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

package io.almostrealism.compute;

/**
 * Identifies the memory address space of a variable or buffer in GPU kernel code.
 *
 * <p>In OpenCL and Metal, buffers must be tagged with their address space:
 * {@link #GLOBAL} for device-accessible memory shared across work groups and
 * {@link #LOCAL} for per-work-group shared memory.</p>
 */
public enum PhysicalScope {
	/** Device-accessible global memory, visible to all work groups and the host. */
	GLOBAL,

	/** Per-work-group shared memory, visible only within a single work group. */
	LOCAL;
}
