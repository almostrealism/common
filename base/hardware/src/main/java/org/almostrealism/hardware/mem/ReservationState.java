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
 * The reservations a {@link KernelMemoryGuard} held against one native address at a single
 * moment.
 *
 * <p>Both counts are read together, so a reservation handed from a scheduling lease to a
 * kernel-execution guard is never observed as neither: the guard takes the execution count
 * before it gives the lease back, and a snapshot taken at any point in between sees at least
 * one of them.</p>
 *
 * @param executions number of kernel executions currently using the memory
 * @param leases     number of scheduling leases currently covering the memory
 */
public record ReservationState(int executions, int leases) {
	/** The state of an address that nothing has reserved. */
	public static final ReservationState NONE = new ReservationState(0, 0);

	/**
	 * Returns whether a kernel execution is currently using the memory.
	 *
	 * @return true if at least one execution reservation is held
	 */
	public boolean isExecuting() { return executions > 0; }

	/**
	 * Returns whether a scheduling lease currently covers the memory.
	 *
	 * @return true if at least one lease is held
	 */
	public boolean isLeased() { return leases > 0; }

	/**
	 * Returns whether nothing holds the memory, so that it may be released.
	 *
	 * @return true if there is neither an execution nor a lease
	 */
	public boolean isReleasable() { return !isExecuting() && !isLeased(); }
}
