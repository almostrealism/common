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

package org.almostrealism.render;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.raytrace.Engine;
import org.almostrealism.raytrace.RayIntersectionEngine;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * {@link RayTracer} is a thin wrapper around an {@link Engine} that optionally provides
 * thread pool-based parallel execution for ray tracing.
 *
 * <p>The tracer delegates the actual ray tracing work to the configured {@link Engine}
 * (typically {@link RayIntersectionEngine}). When
 * {@code enableThreadPool} is true, each ray trace is submitted to an {@link ExecutorService}
 * for potential parallel execution. Otherwise, traces complete synchronously.</p>
 *
 * <p><b>Note:</b> Thread pool execution is disabled by default ({@code enableThreadPool=false}).
 * When enabled, it can improve performance on multi-core systems but adds scheduling overhead.</p>
 *
 * @see Engine
 * @see RayIntersectionEngine
 */
public class RayTracer {
	public static boolean enableThreadPool = false;

	private ExecutorService pool;
	private static long threadCount = 0;

	private Engine engine;
	
	public RayTracer(Engine engine) {
		this(engine, Executors.newFixedThreadPool(10, r -> new Thread(r, "RayTracer Thread " + (threadCount++))));
	}

	public RayTracer(Engine engine, ExecutorService pool) {
		this.engine = engine;
		this.pool = pool;
	}

	public Future<Producer<PackedCollection>> trace(Producer<Ray> r) {
		if (enableThreadPool) {
			Callable<Producer<PackedCollection>> c = () -> engine.trace(r);
			return pool.submit(c);
		} else {
			return CompletableFuture.completedFuture(engine.trace(r));
		}
	}
}
