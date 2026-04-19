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

package org.almostrealism.raytrace;

import io.almostrealism.code.CollectionUtils;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.ProducerWithRank;
import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.Light;
import org.almostrealism.color.RGB;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.color.computations.RankedChoiceEvaluableForRGB;
import org.almostrealism.geometry.Curve;
import org.almostrealism.geometry.DimensionAware;
import org.almostrealism.geometry.Intersectable;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.hardware.DestinationEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@link LightingEngineAggregator} manages multiple {@link LightingEngine} instances and uses
 * ranked choice selection to determine which surface is visible for each ray.
 *
 * <p>The aggregator creates one {@link IntersectionalLightingEngine} for each surface-light pair
 * in the scene. Each lighting engine computes:</p>
 * <ul>
 *   <li>The intersection distance (rank) between the ray and its surface</li>
 *   <li>The RGB color contribution from its light at the intersection point</li>
 * </ul>
 *
 * <p>The aggregator then selects the lighting engine with the smallest positive rank (i.e., the
 * closest surface to the camera along the ray) and returns its color contribution.</p>
 *
 * <p><b>Current Limitation:</b> This design creates surface-light pairs, meaning each surface is
 * evaluated separately for each light. A more efficient design would aggregate lights per surface
 * rather than using ranked choice (see TODO at line ~121). This would reduce redundant intersection
 * calculations.</p>
 *
 * <p><b>Kernel Mode:</b> When constructed with {@code kernel=true}, the aggregator pre-computes
 * all intersection ranks for all pixel positions and caches them for efficient reuse during
 * evaluation. This is significantly faster but requires more memory.</p>
 *
 * @see LightingEngine
 */
public class LightingEngineAggregator extends RankedChoiceEvaluableForRGB implements DimensionAware, ConsoleFeatures {
	/** When true, additional ranked-choice selection details are logged to stdout. */
	public static boolean enableVerbose = false;

	/** Pre-computed pixel-position input buffer used in kernel mode. */
	private PackedCollection input;
	/** Cached rank values for each lighting engine, computed once per kernel invocation. */
	private List<PackedCollection> ranks;

	/** When true, intersection ranks are pre-computed for all pixel positions and cached. */
	private boolean kernel;
	/** Image width, height, and supersampling factors used to resolve pixel positions. */
	private int width, height, ssw, ssh;

	/**
	 * Constructs an aggregator without kernel-mode pre-computation.
	 *
	 * @param r        The ray producer for this pixel
	 * @param surfaces The scene surfaces to evaluate
	 * @param lights   The scene lights to evaluate
	 * @param context  Shader context; may be {@code null} to auto-construct per pair
	 */
	public LightingEngineAggregator(Producer<?> r, Iterable<Curve<PackedCollection>> surfaces,
									Iterable<Light> lights, ShaderContext context) {
		this(r, surfaces, lights, context, false);
	}

	/**
	 * Constructs an aggregator with optional kernel-mode pre-computation.
	 *
	 * @param r        The ray producer for this pixel
	 * @param surfaces The scene surfaces to evaluate
	 * @param lights   The scene lights to evaluate
	 * @param context  Shader context; may be {@code null} to auto-construct per pair
	 * @param kernel   When true, all intersection ranks are pre-computed and cached for the full pixel grid
	 */
	public LightingEngineAggregator(Producer<?> r, Iterable<Curve<PackedCollection>> surfaces,
									Iterable<Light> lights, ShaderContext context, boolean kernel) {
		super(Intersection.e);
		this.kernel = kernel;
		init(r, surfaces, lights, context);
	}

	@Override
	public void setDimensions(int w, int h, int ssw, int ssh) {
		this.width = w;
		this.height = h;
		this.ssw = ssw;
		this.ssh = ssh;

		int totalWidth = w * ssw;
		int totalHeight = h * ssh;

		PackedCollection pixelLocations = Pair.bank(totalWidth * totalHeight);

		for (double i = 0; i < totalWidth; i++) {
			for (double j = 0; j < totalHeight; j++) {
				int index = (int) (j * totalWidth + i);
				Pair p = new Pair(pixelLocations, index * 2);
				p.setMem(new double[] { i / ssw, j / ssh });
			}
		}

		setKernelInput(pixelLocations);

		stream().filter(p -> p instanceof DimensionAware)
				.forEach(p -> ((DimensionAware) p).setDimensions(width, height, ssw, ssh));
	}

	/**
	 * Provide a {@link MemoryBank} to use when evaluating the rank for each
	 * {@link LightingEngine}.
	 */
	private void setKernelInput(PackedCollection input) {
		this.input = input;
		resetRankCache();
	}

	/**
	 * Run rank computations for all {@link LightingEngine}s, if they are not already available.
	 */
	public synchronized void initRankCache() {
		if (this.ranks != null) return;
		if (this.input == null)
			throw new IllegalArgumentException("Kernel input must be specified ahead of rank computation");

		this.ranks = new ArrayList<>();
		for (int i = 0; i < size(); i++) {
			PackedCollection rankCollection = new PackedCollection(shape(input.getCount(), 1).traverse(1));
			this.ranks.add(rankCollection);

			Producer rankProducer = get(i).getRank();
			rankProducer.get().into(rankCollection.each()).evaluate(input);
		}
	}

	/**
	 * Destroy the cache of rank for {@link LightingEngine}s.
	 */
	public void resetRankCache() {
		this.ranks = null;
	}

	// TODO  Rename this class to SurfaceLightingAggregator and have LightingEngineAggregator sum the lights instead of rank choice them
	/**
	 * Populates the aggregator with one {@link IntersectionalLightingEngine} for each surface-light pair.
	 *
	 * <p>For each combination of surface and light, a {@link ShaderContext} is constructed and an
	 * {@link IntersectionalLightingEngine} is added to the ranked-choice list.</p>
	 *
	 * @param r        The ray producer
	 * @param surfaces The scene surfaces
	 * @param lights   The scene lights
	 * @param context  Base shader context to clone per pair, or {@code null} to construct from scratch
	 */
	protected void init(Producer<?> r, Iterable<Curve<PackedCollection>> surfaces, Iterable<Light> lights, ShaderContext context) {
		for (Curve<PackedCollection> s : surfaces) {
			for (Light l : lights) {
				Collection<Curve<PackedCollection>> otherSurfaces = CollectionUtils.separate(s, surfaces);
				Collection<Light> otherLights = CollectionUtils.separate(l, lights);

				ShaderContext c;

				if (context == null) {
					c = new ShaderContext(s, l);
					c.setOtherSurfaces(otherSurfaces);
					c.setOtherLights(otherLights);
				} else {
					c = context.clone();
					c.setSurface(s);
					c.setLight(l);
					c.setOtherSurfaces(otherSurfaces);
					c.setOtherLights(otherLights);
				}

				// TODO Choose which engine dynamically
				this.add(new IntersectionalLightingEngine(r, (Intersectable) s,
															otherSurfaces,
															l, otherLights,
															c));
			}
		}
	}

	@Override
	public PackedCollection evaluate(Object args[]) {
		if (!kernel) return super.evaluate(args);

		initRankCache();

		Pair pos = (Pair) args[0];

		Producer<PackedCollection> best = null;
		double rank = Double.MAX_VALUE;

		int position = DimensionAware.getPosition(pos.getX(), pos.getY(), width, height, ssw, ssh);

		int x = (int) pos.getX();
		int y = (int) pos.getY();
		boolean printLog = enableVerbose;

		if (printLog) {
			log("RankedChoiceProducer: pixel(" + x + "," + y + ") -> position=" + position);
			log("  There are " + size() + " Producers to choose from");
		}

		r: for (int i = 0; i < size(); i++) {
			ProducerWithRank p = get(i);

			// Use valueAt(position, 0) for shape (N, 1) instead of get(position).getValue() for Scalar
			double r = ranks.get(i).valueAt(position, 0);
			if (printLog) log("  Engine " + i + ": rank[" + position + "] = " + r);
			if (r < e && printLog) log("  " + p + " was skipped due to being less than " + e);
			if (r < e) continue r;

			if (best == null) {
				if (printLog) log(p + " was assigned (rank = " + r + ")");
				best = (Producer<PackedCollection>) p.getProducer();
				rank = r;
			} else {
				if (r >= e && r < rank) {
					if (printLog) log(p + " was assigned (rank = " + r + ")");
					best = (Producer<PackedCollection>) p.getProducer();
					rank = r;
				}
			}

			if (rank <= e) break r;
		}

		if (printLog) log(best + " was chosen\n----------");

		if (best == null) return null;

		Object result = best.get().evaluate(args);
		RGB color;
		if (result instanceof RGB) {
			color = (RGB) result;
		} else if (result instanceof PackedCollection) {
			color = new RGB((PackedCollection) result, 0);
		} else {
			throw new IllegalStateException("Unexpected result type: " +
				(result == null ? "null" : result.getClass().getName()));
		}

		if (printLog) {
			log("  Color evaluated: RGB(" + color.getRed() + ", " + color.getGreen() + ", " + color.getBlue() + ")");
		}

		return color;
	}

	@Override
	public Evaluable<PackedCollection> into(Object destination) {
		return new DestinationEvaluable<>(this, (MemoryBank) destination);
	}
}
