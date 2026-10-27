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

package org.almostrealism.hardware.mem;

/**
 * Where a request to release a native memory block came from.
 *
 * <p>{@link HardwareMemoryProvider} receives release requests from two sources: an owner
 * explicitly releasing its memory, and the garbage collector reporting that the {@link RAM}
 * behind a {@link NativeRef} has become unreachable. A block is freed once whichever request
 * claims it first ({@link MemoryReference#tryClaimFreed(ReleaseOrigin)}) wins. A lost claim is
 * a double free only when both the winning and the losing request were explicit; a collected
 * request racing an explicit one is the expected outcome of the two paths overlapping, and is
 * not reported.</p>
 */
enum ReleaseOrigin {
	/** An owner asked for the memory to be released ({@link HardwareMemoryProvider#deallocate(int, RAM)}). */
	EXPLICIT,

	/** The garbage collector found the {@link RAM} unreachable and enqueued its reference. */
	COLLECTED;

	/**
	 * Tells whether a request from this origin that lost its claim to a request from
	 * {@code winner} is a double free. Only two explicit requests for the same block are:
	 * that means two owners each believed they held the memory. Any loss involving the
	 * collected path is the expected overlap of the two release paths.
	 *
	 * @param winner the origin of the request whose claim succeeded, or {@code null} if the
	 *               claim has since been reversed
	 * @return {@code true} if losing the claim to {@code winner} is a double free
	 */
	boolean isDoubleFreeAfter(ReleaseOrigin winner) {
		return this == EXPLICIT && winner == EXPLICIT;
	}
}
