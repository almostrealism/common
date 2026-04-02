/*
 * Copyright 2020 Michael Murray
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


package org.almostrealism.light;

import org.almostrealism.texture.IntensityMap;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.collect.PackedCollection;

/**
 * A cubic volumetric light source that samples emission positions from an {@link IntensityMap}.
 * <p>
 * The {@code CubeLight} uses rejection sampling against the intensity map to choose
 * emission positions that are weighted by the map's intensity values. The cube dimensions
 * default to 1x1x1 and can be scaled independently on each axis.
 * </p>
 */
public class CubeLight extends LightBulb implements VectorFeatures {
	/** The intensity map used to weight emission position sampling within the cube. */
	private IntensityMap map;

	/** Width of the cube along the x axis (in scene units, typically micrometers). */
	private double width;

	/** Height of the cube along the y axis (in scene units, typically micrometers). */
	private double height;

	/** Depth of the cube along the z axis (in scene units, typically micrometers). */
	private double depth;

	/** Cached emission position from the last call to {@link #getEmitPosition()}. */
	private Vector pos;

	/**
	 * Constructs a {@code CubeLight} with no intensity map.
	 */
	public CubeLight() {
		this(null);
	}

	/**
	 * Constructs a {@code CubeLight} with the specified intensity map.
	 *
	 * @param map  the intensity map used for emission position sampling, or {@code null}
	 *             to use uniform random sampling
	 */
	public CubeLight(IntensityMap map) {
		this.map = map;
		this.width = 1.0;
		this.height = 1.0;
		this.depth = 1.0;
	}
	
	public void setEmitPositionMap(IntensityMap map) { this.map = map; }
	public IntensityMap getEmitPositionMap() { return this.map; }
	
	public void setWidth(double w) { this.width = w; }
	public void setHeight(double h) { this.height = h; }
	public void setDepth(double d) { this.depth = d; }
	public double getWidth() { return this.width; }
	public double getHeight() { return this.height; }
	public double getDepth() { return this.depth; }

	@Override
	public double getEmitEnergy() {
		// TODO use another intensity map + spectra
		return super.getEmitEnergy();
	}

	@Override
	public Producer<PackedCollection> getEmitPosition() {
		if (this.pos != null) return v(this.pos);
		
		double x = 0.0, y = 0.0, z = 0.0;
		double r = 1.0;
		double p = 0.0;
		
		while (r >= p) {
			x = Math.random();
			y = Math.random();
			z = Math.random();
			r = Math.random();
			p = this.map.getIntensity(x, y, z);
		}
		
		this.pos = new Vector(this.width * (x - 0.5),
								this.height * (y - 0.5),
								this.depth * (z - 0.5));
		
		return v(this.pos);
	}
}
