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

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.ZeroVector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.ProbabilityDistribution;
import org.almostrealism.color.Transparent;
import org.almostrealism.geometry.UniformSphericalRandom;
import org.almostrealism.physics.Absorber;
import org.almostrealism.physics.Clock;
import org.almostrealism.physics.PhysicalConstants;
import org.almostrealism.physics.Volume;
import org.almostrealism.stats.SphericalProbabilityDistribution;

/**
 * A {@link LightBulb} emits photons with wavelengths between 380 nanometers
 * and 780 nanometers.
 * 
 * @author  Michael Murray
 */
public class LightBulb implements Volume<Object>, Absorber, Transparent, PhysicalConstants {
	protected Clock clock;
	
	protected double power, delta, last;
	protected ProbabilityDistribution spectra;
	protected SphericalProbabilityDistribution brdf;
	
	private final double specEnd = H * C / 0.380;
	private final double specStart = H * C / 0.780;
	protected double specAvg = (specStart + specEnd) / 2.0;
	
	/**
	 * @param p  Power rating of this light bulb in eV/msec. Watts can be converted
	 *           to this measurement by multiplying by LightBulb.wattsToEvMsec.
	 */
	public void setPower(double p) {
		this.power = p;
		this.delta = this.specAvg / this.power;
	}
	
	/**
	 * @return  Power rating of this light bulb in eV/msec. This can be converted to watts
	 *          by multiplying by LightBulb.evMsecToWatts.
	 */
	public double getPower() { return this.power; }
	
	/**
	 * Returns false. A {@link LightBulb} does not absorb photons.
	 */
	@Override
	public boolean absorb(Vector x, Vector p, double energy) { return false; }
	
	/** Returns a uniform spherical random vector. */
	@Override
	public Producer<PackedCollection> emit() {
		this.last += this.delta;
		return () -> args -> UniformSphericalRandom.getInstance().evaluate(args);
	}

	/** Returns a random energy value in the visible spectrum. */
	@Override
	public double getEmitEnergy() {
		if (this.spectra == null)
			return this.specStart + Math.random() * (this.specEnd - this.specStart);
		else
			return this.spectra.getSample(Math.random());
	}
	
	/**
	 * Time in microseconds until the next photon should be emitted. The frequency
	 * of photon emission is related to the power of the {@link LightBulb}.
	 */
	@Override
	public double getNextEmit() {
		return this.last + this.delta - this.clock.getTime();
	}
	
	/** Returns the location of this {@link LightBulb}. */
	@Override
	public Producer<PackedCollection> getEmitPosition() { return ZeroVector.getInstance(); }
	
	public void setSpectra(ProbabilityDistribution spectra) { this.spectra = spectra; }
	public ProbabilityDistribution getSpectra() { return this.spectra; }
	
	public void setBRDF(SphericalProbabilityDistribution brdf) { this.brdf = brdf; }
	public SphericalProbabilityDistribution getBRDF() { return this.brdf; }

	@Override
	public void setClock(Clock c) { this.clock = c; }

	@Override
	public Clock getClock() { return this.clock; }

	@Override
	public Producer getValueAt(Producer point) { return null; }

	// TODO  This should use the brdf
	@Override
	public Producer<PackedCollection> getNormalAt(Producer<PackedCollection> x) { return () -> args -> UniformSphericalRandom.getInstance().evaluate(args); }

	@Override
	public boolean inside(Producer<PackedCollection> x) { return false; }

	@Override
	public double intersect(Vector p, Vector d) { return Double.MAX_VALUE - 1.0; }

	@Override
	public double[] getSpatialCoords(double[] uv) { return new double[3]; }

	@Override
	public double[] getSurfaceCoords(Producer<PackedCollection> xyz) { return new double[2]; }
}
