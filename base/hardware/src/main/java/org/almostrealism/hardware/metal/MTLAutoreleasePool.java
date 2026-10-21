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

package org.almostrealism.hardware.metal;

/**
 * Wrapper for an Objective-C autorelease pool ({@code NS::AutoreleasePool}) on the thread that
 * created it.
 *
 * <p>metal-cpp factory methods such as {@code commandBuffer()} and
 * {@code computeCommandEncoder()} return autoreleased objects. On a long-lived JNI worker thread
 * with no pool in place these are never reclaimed and accumulate in the Metal driver until it
 * stalls. Running each piece of work that issues Metal commands through {@link #run(Runnable)}
 * drains what the work autoreleased once it ends, which frees the encoders it created. An object
 * that must outlive the work has to be retained explicitly, as every command buffer is (see
 * {@link MTL#commandBuffer(long)}); the drain then releases only its autorelease reference.</p>
 *
 * <p>Objective-C requires a pool to be drained on the thread that created it, in the reverse
 * order of creation. A pool therefore exists only for the duration of {@link #run(Runnable)},
 * which creates it on the calling thread before the work and releases it on the same thread
 * once the work ends. No other code can obtain an {@code MTLAutoreleasePool}, and nothing else
 * calls the package-private natives it wraps ({@code MTL.autoreleasePoolPush()} and
 * {@code MTL.autoreleasePoolPop(long)}), so no pool is ever drained out of order or from another
 * thread.</p>
 *
 * @see MetalCommandRunner
 */
public final class MTLAutoreleasePool extends MTLObject {
	/**
	 * Creates an autorelease pool on the calling thread, which collects every object
	 * autoreleased on this thread until it is released.
	 */
	private MTLAutoreleasePool() {
		super(MTL.autoreleasePoolPush());
	}

	/**
	 * Drains this pool, releasing every object autoreleased on the calling thread since the pool
	 * was created, and then the pool itself. {@link #run(Runnable)} calls this on the thread that
	 * created the pool.
	 */
	@Override
	public void release() {
		MTL.autoreleasePoolPop(getNativePointer());
		super.release();
	}

	/**
	 * Runs {@code task} on the calling thread inside a fresh autorelease pool, which is drained
	 * when the task ends, whether or not it succeeds.
	 *
	 * @param task the work to run inside the pool
	 */
	public static void run(Runnable task) {
		MTLAutoreleasePool pool = new MTLAutoreleasePool();

		try {
			task.run();
		} finally {
			pool.release();
		}
	}
}
