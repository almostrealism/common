/*
 * Copyright 2016 Michael Murray
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

package org.almostrealism.primitives;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.ProbabilityDistribution;
import org.almostrealism.color.Spectrum;
import org.almostrealism.physics.Clock;
import org.almostrealism.physics.Fast;
import org.almostrealism.physics.PhysicalConstants;
import org.almostrealism.physics.VolumeAbsorber;
import org.almostrealism.space.distribution.UniformHemisphericalDistribution;
import org.almostrealism.stats.BRDF;
import org.almostrealism.stats.SphericalProbabilityDistribution;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * An absorber that captures photons inside its {@link #setVolume volume} and re-emits each one in a
 * direction chosen by its {@link #setBRDF BRDF}, uniform over the hemisphere by default. Without a
 * volume it absorbs nothing, since it needs a surface normal to re-emit.
 *
 * <p>Photons can be filtered by wavelength, either to a {@link #setColorRange(double, double) range}
 * or by a {@link #setSpectra(ProbabilityDistribution) spectrum} giving the probability of keeping
 * each wavelength. A filtered photon is absorbed and never re-emitted. Each kept photon is
 * re-emitted after the {@link #setAbsorbDelay(double) absorb delay} in effect when it arrives.</p>
 *
 * @author  Samuel Tepper
 */
public class SpecularAbsorber extends VolumeAbsorber implements Fast, BRDF, Spectrum, PhysicalConstants, CodeFeatures {
	/** An absorbed photon waiting to be re-emitted. */
	private static class Photon {
		/** Where the photon was absorbed. */
		private final Vector position;

		/** The direction the photon was travelling. */
		private final Vector direction;

		/** The photon's energy, in electron volts. */
		private final double energy;

		/** The clock time at which the photon is re-emitted. */
		private final double time;

		/**
		 * Creates a waiting photon.
		 *
		 * @param position  where it was absorbed
		 * @param direction the direction it was travelling
		 * @param energy    its energy, in electron volts
		 * @param time      the clock time at which it is re-emitted
		 */
		Photon(Vector position, Vector direction, double energy, double time) {
			this.position = position;
			this.direction = direction;
			this.energy = energy;
			this.time = time;
		}
	}

	/** The photons waiting to be re-emitted, earliest first. */
	private final PriorityQueue<Photon> queue = new PriorityQueue<>(Comparator.comparingDouble(p -> p.time));

	/** The clock that times re-emission. */
	private Clock clock;

	/** The distribution of re-emitted directions. */
	private SphericalProbabilityDistribution brdf;

	/** The probability of keeping each wavelength, in micrometres, or null to keep all of them. */
	private ProbabilityDistribution spectra;

	/** The depth test applied when re-emitting; zero for none. */
	private double reflectDepth;

	/** The depth test applied when absorbing; zero for none. */
	private double absorbDepth;

	/** The shortest wavelength kept, in nanometres, or zero to keep all of them. */
	private double startWavelength;

	/** The width of the kept wavelength range, in nanometres. */
	private double range;

	/** The time between absorbing a photon and re-emitting it. */
	private double delay;

	/** The position relative to the absorber at which the last photon reached it. */
	private double[] origPosition;

	/** Creates an absorber with no volume and a uniform hemispherical BRDF. */
	public SpecularAbsorber() {
		super.absorber = this;
		this.brdf = new UniformHemisphericalDistribution();
	}

	/**
	 * Sets the time between absorbing a photon and re-emitting it, for photons absorbed from now on.
	 *
	 * @param t the delay
	 */
	@Override
	public void setAbsorbDelay(double t) { this.delay = t; }

	/**
	 * Records the position relative to this absorber at which a photon reached it.
	 *
	 * @param x {x, y, z}
	 */
	@Override
	public void setOrigPosition(double[] x) { this.origPosition = x; }

	/**
	 * Returns the position relative to this absorber at which the last photon reached it.
	 *
	 * @return {x, y, z}, or null if none has been recorded
	 */
	public double[] getOrigPosition() { return this.origPosition; }

	/**
	 * Returns the current clock time, or zero without a clock.
	 *
	 * @return the time
	 */
	private double now() { return clock == null ? 0.0 : clock.getTime(); }

	/**
	 * Applies a depth test: with a positive depth, it fails when the volume's surface is more than
	 * that far behind the photon and at least that far ahead; with a negative depth, when it is less
	 * than that far behind and at most that far ahead. A depth of zero always passes.
	 *
	 * @param depth     the depth
	 * @param position  the photon's position
	 * @param direction the photon's direction
	 * @return true if the test fails
	 */
	private boolean beyondDepth(double depth, Vector position, Vector direction) {
		if (depth == 0.0) return false;

		double behind = this.volume.intersect(position, direction.minus());
		if (depth > 0.0) {
			return behind > depth && this.volume.intersect(position, direction) >= depth;
		}

		return behind < -depth && this.volume.intersect(position, direction) <= -depth;
	}

	/**
	 * Returns whether a photon of the given wavelength is kept for re-emission.
	 *
	 * @param wavelength the wavelength, in micrometres
	 * @return true if it is kept
	 */
	private boolean keeps(double wavelength) {
		if (startWavelength > 0.0) {
			double nanometres = wavelength * 1000.0;
			if (nanometres < startWavelength || nanometres > startWavelength + range) return false;
		}

		return spectra == null || Math.random() < spectra.getProbability(wavelength);
	}

	@Override
	public boolean absorb(Vector position, Vector incoming, double energy) {
		if (this.volume == null || !this.volume.inside(v(position))) return false;
		if (beyondDepth(absorbDepth, position, incoming)) return false;

		if (keeps(HC / energy)) {
			queue.add(new Photon(position, incoming, energy, now() + delay));
		}

		return true;
	}

	/**
	 * Re-emits the earliest waiting photon. If it fails the reflect depth test it passes through
	 * unchanged; otherwise its direction comes from the BRDF, with the surface normal turned to
	 * face back along the photon's path.
	 *
	 * @return the direction of the re-emitted photon
	 * @throws IllegalStateException if no photon is waiting
	 */
	@Override
	public Producer<PackedCollection> emit() {
		Photon photon = queue.poll();
		if (photon == null) throw new IllegalStateException("No photon is waiting to be emitted");

		if (beyondDepth(reflectDepth, photon.position, photon.direction)) return v(photon.direction);

		Vector back = photon.direction.minus();
		Vector n = new Vector(this.volume.getNormalAt(v(photon.position)).get().evaluate(), 0);
		if (n.dotProduct(back) < 0) n = n.minus();
		return this.brdf.getSample(back.toArray(), n.toArray());
	}

	/**
	 * Returns the energy of the earliest waiting photon.
	 *
	 * @return the energy, in electron volts
	 * @throws IllegalStateException if no photon is waiting
	 */
	@Override
	public double getEmitEnergy() {
		Photon photon = queue.peek();
		if (photon == null) throw new IllegalStateException("No photon is waiting to be emitted");
		return photon.energy;
	}

	/**
	 * Returns the time until the earliest waiting photon is re-emitted.
	 *
	 * @return the time, or {@link Double#MAX_VALUE} if no photon is waiting
	 */
	@Override
	public double getNextEmit() {
		Photon photon = queue.peek();
		return photon == null ? Double.MAX_VALUE : photon.time - now();
	}

	/**
	 * Returns where the earliest waiting photon was absorbed.
	 *
	 * @return the position, or null if no photon is waiting
	 */
	@Override
	public Producer<PackedCollection> getEmitPosition() {
		Photon photon = queue.peek();
		return photon == null ? null : v(photon.position);
	}

	@Override
	public void setClock(Clock c) { this.clock = c; }

	@Override
	public Clock getClock() { return this.clock; }

	/**
	 * Sets the depth test applied when absorbing: with a positive depth a photon is refused when the
	 * volume's surface is more than that far behind it and at least that far ahead.
	 *
	 * @param depth the depth, or zero for no test
	 */
	public void setAbsorbDepth(double depth) { this.absorbDepth = depth; }

	/**
	 * Returns the absorb depth test.
	 *
	 * @return the depth, or zero for no test
	 */
	public double getAbsorbDepth() { return this.absorbDepth; }

	/**
	 * Sets the depth test applied when re-emitting: a photon that fails it passes through unchanged.
	 * The test is the same as for {@link #setAbsorbDepth(double)}.
	 *
	 * @param depth the depth, or zero for no test
	 */
	public void setReflectDepth(double depth) { this.reflectDepth = depth; }

	/**
	 * Returns the reflect depth test.
	 *
	 * @return the depth, or zero for no test
	 */
	public double getReflectDepth() { return this.reflectDepth; }

	/**
	 * Sets the probability of keeping each wavelength.
	 *
	 * @param spectra a distribution over wavelengths in micrometres, or null to keep all of them
	 */
	public void setSpectra(ProbabilityDistribution spectra) { this.spectra = spectra; }

	@Override
	public ProbabilityDistribution getSpectra() { return this.spectra; }

	@Override
	public void setBRDF(SphericalProbabilityDistribution brdf) { this.brdf = brdf; }

	@Override
	public SphericalProbabilityDistribution getBRDF() { return this.brdf; }

	/**
	 * Sets the shortest wavelength kept.
	 *
	 * @param s the wavelength, in nanometres, or zero to keep all wavelengths
	 */
	public void setColorRangeStart(double s) { this.startWavelength = s; }

	/**
	 * Returns the shortest wavelength kept.
	 *
	 * @return the wavelength, in nanometres, or zero if all wavelengths are kept
	 */
	public double getColorRangeStart() { return this.startWavelength; }

	/**
	 * Sets the width of the kept wavelength range.
	 *
	 * @param l the width, in nanometres
	 */
	public void setColorRangeLength(double l) { this.range = l; }

	/**
	 * Returns the width of the kept wavelength range.
	 *
	 * @return the width, in nanometres
	 */
	public double getColorRangeLength() { return this.range; }

	/**
	 * Returns the kept wavelength range.
	 *
	 * @return {start, width}, in nanometres
	 */
	public double[] getColorRange() { return new double[] {this.startWavelength, this.range}; }

	/**
	 * Keeps only the photons whose wavelength falls in the given range.
	 *
	 * @param startWavelength the shortest wavelength kept, in nanometres
	 * @param range           the width of the range, in nanometres
	 */
	public void setColorRange(double startWavelength, double range) {
		this.startWavelength = startWavelength;
		this.range = range;
	}
}
