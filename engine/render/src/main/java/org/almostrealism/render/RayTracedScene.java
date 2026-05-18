/*
 * Copyright 2022 Michael Murray
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
import io.almostrealism.relation.Realization;
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Pair;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.color.RealizableImage;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.geometry.Camera;
import org.almostrealism.geometry.DimensionAware;
import org.almostrealism.raytrace.Engine;
import org.almostrealism.raytrace.FogParameters;
import org.almostrealism.raytrace.RayIntersectionEngine;
import org.almostrealism.raytrace.RenderParameters;
import org.almostrealism.space.Scene;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link RayTracedScene} coordinates the ray tracing process for rendering an image of a scene.
 * It integrates the {@link Engine}, {@link Camera}, and {@link RenderParameters} to produce
 * a {@link RealizableImage} that can be evaluated to generate pixel data.
 *
 * <p>The rendering pipeline:</p>
 * <ol>
 *   <li>Camera generates rays for each pixel position (including supersampling)</li>
 *   <li>Rays are traced through the scene using the configured {@link Engine}</li>
 *   <li>Each ray produces a {@link Producer} for an RGB color</li>
 *   <li>Producers are assembled into a {@link RealizableImage}</li>
 *   <li>Evaluation of the RealizableImage computes all colors and returns a 2D RGB array</li>
 * </ol>
 *
 * <p><b>Supersampling:</b> When supersample width/height are greater than 1, multiple rays are
 * cast per pixel and averaged together for anti-aliasing.</p>
 *
 * <p><b>Execution Model:</b> Ray tracing can optionally use an {@link ExecutorService} for
 * parallel execution (controlled by {@link RayTracer#enableThreadPool}), though by default
 * it builds a computation graph that is evaluated synchronously.</p>
 *
 * @see RayTracer
 * @see Engine
 * @see RayIntersectionEngine
 */
public class RayTracedScene implements Realization<RealizableImage, RenderParameters>, CodeFeatures, RGBFeatures, ConsoleFeatures {
	/** The ray tracer that delegates to the rendering engine for each ray. */
	private RayTracer tracer;
	/** The camera that generates rays from pixel positions. */
	private Camera camera;
	/** The current render parameters (resolution, supersampling, viewport offsets). */
	private RenderParameters p;
	
	/**
	 * Controls whether the color of a point light source will be adjusted based on the
	 * intensity of the point light or whether this will be left up to the shader.
	 * By default it is set to true.
	 */
	public static boolean premultiplyIntensity = true;
	
	/** The black color constant used as a fallback when tracing produces no result. */
	public static RGB black = new RGB(0.0, 0.0, 0.0);

	/**
	 * Constructs a {@link RayTracedScene} with the given engine and camera, using default render parameters.
	 *
	 * @param t The rendering engine
	 * @param c The camera for ray generation
	 */
	public RayTracedScene(Engine t, Camera c) {
		this(t, c, null);
	}

	/**
	 * Constructs a {@link RayTracedScene} with the given engine, camera, and render parameters.
	 *
	 * @param t The rendering engine
	 * @param c The camera for ray generation
	 * @param p The render parameters; may be {@code null}
	 */
	public RayTracedScene(Engine t, Camera c, RenderParameters p) {
		this(t, c, p, null);
	}

	/**
	 * Constructs a {@link RayTracedScene} with the given engine, camera, render parameters, and optional thread pool.
	 *
	 * @param t    The rendering engine
	 * @param c    The camera for ray generation
	 * @param p    The render parameters; may be {@code null}
	 * @param pool An optional executor service for parallel ray tracing; may be {@code null}
	 */
	public RayTracedScene(Engine t, Camera c, RenderParameters p, ExecutorService pool) {
		this.tracer = pool == null ? new RayTracer(t) : new RayTracer(t, pool);
		this.camera = c;
		this.p = p;
	}

	/**
	 * Convenience constructor that creates a {@link RayIntersectionEngine} from the given scene and fog parameters.
	 *
	 * @param scene The scene to render
	 * @param fog   Fog parameters for atmospheric effects
	 * @param p     The render parameters
	 */
	public RayTracedScene(Scene<? extends ShadableSurface> scene, FogParameters fog, RenderParameters p) {
		this(new RayIntersectionEngine(scene, fog), scene.getCamera(), p, null);
	}

	/**
	 * Returns the current render parameters.
	 *
	 * @return The render parameters, or {@code null} if not set
	 */
	public RenderParameters getRenderParameters() { return p; }

	/**
	 * Traces a ray for the given UV pixel position and supersampling displacement,
	 * returning a producer for the resulting color.
	 *
	 * @param uv The UV pixel position as a {@link Pair}
	 * @param sd The supersampling displacement as a {@link Pair}
	 * @return A producer that computes the RGB color for this pixel, or black if tracing fails
	 */
	public Producer<PackedCollection> operate(Producer<Pair> uv, Producer<Pair> sd) {
		Future<Producer<PackedCollection>> color = tracer.trace((Producer) camera.rayAt(uv, sd));

		if (color == null) {
			color = new Future<>() {
				@Override
				public Producer<PackedCollection> get() {
					return black();
				}

				@Override
				public boolean cancel(boolean mayInterruptIfRunning) {return false;}

				@Override
				public boolean isCancelled() {return false;}

				@Override
				public boolean isDone() {return true;}

				@Override
				public Producer<PackedCollection> get(long timeout, TimeUnit unit)
						throws InterruptedException, ExecutionException, TimeoutException {
					return get();
				}
			};
		}

		try {
			return color.get();
		} catch (InterruptedException | ExecutionException e) {
			warn(e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Builds the color producer using the current render parameters.
	 *
	 * @return A producer that computes colors for all pixels given a pixel position
	 */
	public Producer<PackedCollection> getProducer() { return getProducer(getRenderParameters()); }

	/**
	 * Builds a color producer for all pixels given the specified render parameters.
	 *
	 * <p>Creates a pixel position input with the total number of pixels (including supersamples),
	 * then calls {@link #operate} to build the computation graph. If the result implements
	 * {@link DimensionAware}, dimensions are configured accordingly.</p>
	 *
	 * @param p The render parameters specifying resolution and supersampling
	 * @return A producer that computes colors for all pixels
	 */
	public Producer<PackedCollection> getProducer(RenderParameters p) {
		int totalPixels = p.width * p.ssWidth * p.height * p.ssHeight;
		Producer<PackedCollection> producer = operate(v(shape(totalPixels, 2), 0), (Producer) pair(p.width, p.height));

		if (producer instanceof DimensionAware) {
			((DimensionAware) producer).setDimensions(p.width, p.height, p.ssWidth, p.ssHeight);
		}

		return producer;
	}

	@Override
	public RealizableImage realize(RenderParameters p) {
		this.p = p;

		Pixel px = new Pixel(p.ssWidth, p.ssHeight);
		Producer<PackedCollection> producer = getProducer(p);

		for (int i = 0; i < p.ssWidth; i++) {
			for (int j = 0; j < p.ssHeight; j++) {
				px.setSample(i, j, producer);
			}
		}

		return new RealizableImage(px, new Pair(p.dx, p.dy));
	}
}
