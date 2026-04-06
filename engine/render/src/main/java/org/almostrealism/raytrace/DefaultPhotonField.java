/*
 * Copyright 2016 Michael Murray
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

package org.almostrealism.raytrace;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.physics.Absorber;
import org.almostrealism.physics.AbsorberSet;
import org.almostrealism.physics.Clock;
import org.almostrealism.physics.PhotonField;
import org.almostrealism.physics.PhysicalConstants;
import org.almostrealism.util.Chart;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * {@link DefaultPhotonField} is the standard implementation of {@link PhotonField} that
 * simulates a collection of photons propagating through space and interacting with absorbers.
 *
 * <p>Each photon is stored as an {@code Object[]} tuple containing:</p>
 * <ol>
 *   <li>Position as a {@link org.almostrealism.algebra.Vector}</li>
 *   <li>Direction (momentum) as a {@link org.almostrealism.algebra.Vector}</li>
 *   <li>Energy as a {@code double[]}</li>
 *   <li>Remaining distance to the next absorber (or -1.0 if not yet computed) as a {@code double[]}</li>
 * </ol>
 *
 * <p>On each {@link #tick(double)} call, all photons are advanced by {@code direction * C * dt},
 * then checked for absorption. The absorber may also emit new photons during the tick.</p>
 *
 * <p>Performance metrics (photon set size, wall-clock time, and cost rate) are tracked via
 * {@link Chart} instances and can optionally be written to a CSV file.</p>
 *
 * @see PhotonField
 * @see org.almostrealism.physics.Absorber
 * @author Michael Murray
 */
// TODO  Consider creating a custom list for photon set (tick creates many many double[][]).
public class DefaultPhotonField implements PhotonField {
	/** Fraction of ticks for which verbose photon movement output is logged (0.0 disables logging). */
	public static double verbose = 0.01;
	/** When true, emitted photon directions are validated to have unit length. */
	public static boolean checkLength = true;
	/** Epsilon used when validating that emitted direction vectors have length approximately 1.0. */
	public static double ep = Math.pow(10.0, -10.0);

	/** The simulation clock providing current time and tick interval. */
	private Clock clock;
	/** The live set of photons, each stored as an Object[] tuple. */
	private final Set<Object[]> photons;
	/** The single absorber that all photons interact with. */
	private Absorber absorber;
	/** The time step granularity for this field's updates. */
	private long delta = 1;
	/** Maximum photon lifetime expressed as a distance (lifetime * C). */
	private double lifetime = Double.MAX_VALUE - 1.0;
	/** When true, distance-to-absorber is tracked for efficient intersection skipping. */
	private boolean trace = true;

	/** Chart recording photon set size over time. */
	private final Chart sizeChart;
	/** Chart recording wall-clock time per simulation hour. */
	private final Chart timeChart;
	/** Chart recording estimated cost rate per simulation hour. */
	private final Chart costChart;
	/** Total number of ticks processed; used to determine log frequency. */
	private long tot = 0, log = 500;
	/** True before the first tick; used to capture the start time. */
	private boolean first;
	/** System time (ms) when the first tick was processed. */
	private long start;
	/** Optional path to a file where the size chart data is periodically written. */
	private String file;
	
	/**
	 * Creates a new {@link DefaultPhotonField} with an empty photon set and
	 * initializes the three performance charts with a capacity of 500,000 entries each.
	 */
	public DefaultPhotonField() {
		this.photons = new HashSet();

		this.sizeChart = new Chart(500000);
		this.sizeChart.setScale(200);
		this.sizeChart.setDivisions(500);
		
		this.timeChart = new Chart(500000);
		this.timeChart.setScale(200);
		this.timeChart.setDivisions(500);

		this.costChart = new Chart(500000);
		this.costChart.setScale(200);
		this.costChart.setDivisions(500);
	}

	@Override
	public void addPhoton(double[] x, double[] p, double energy) {
		// TODO  Replace with a single PackedCollection (dim 8 - ray plus two scalars)
		this.photons.add(new Object[] {new Vector(x), new Vector(p), new double[] { energy }, new double[] { 0.0 }});
	}

	@Override
	public void setAbsorber(Absorber absorber) { this.absorber = absorber; }

	@Override
	public Absorber getAbsorber() { return this.absorber; }

	@Override
	public void setGranularity(long delta) { this.delta = delta; }

	@Override
	public long getGranularity() { return this.delta; }
	
	public void setLogFrequency(long ticks) { this.log = ticks; }
	public long getLogFrequency() { return this.log; }
	
	public void setLogFile(String file) { this.file = file; }
	public String getLogFile() { return this.file; }
	
	public Chart getSizeChart() { return this.sizeChart; }
	public Chart getTimeChart() { return this.timeChart; }
	public Chart getCostChart() { return this.costChart; }
	
	public void setRayTracing(boolean trace) { this.trace = trace; }
	public boolean getRayTracing() { return this.trace; }

	@Override
	public double getEnergy(double[] x, double radius) {
		Iterator<Object[]> itr = this.photons.iterator();
		Vector center = new Vector(x);
		double e = 0.0;

		while (itr.hasNext()) {
			Object[] p = itr.next();
			Vector pos = (Vector) p[0];

			if (pos.subtract(center).length() < radius)
				e += ((double[]) p[2])[0];
		}

		return e;
	}

	@Override
	public long getSize() { return this.photons.size(); }

	@Override
	public void setMaxLifetime(double l) { this.lifetime = l * PhysicalConstants.C; }

	@Override
	public double getMaxLifetime() { return this.lifetime / PhysicalConstants.C; }

	@Override
	public int removePhotons(double[] x, double radius) {
		Iterator<Object[]> itr = this.photons.iterator();
		Vector center = new Vector(x);
		int removed = 0;

		while (itr.hasNext()) {
			Object[] p = itr.next();
			Vector pos = (Vector) p[0];

			if (pos.subtract(center).length() < radius) {
				itr.remove();
				removed++;
			}
		}

		return removed;
	}

	@Override
	public void setClock(Clock c) {
		this.clock = c;
		if (this.absorber != null) this.absorber.setClock(this.clock);
	}

	@Override
	public Clock getClock() { return this.clock; }

	@Override
	public void tick(double s) {
		if (this.first) {
			this.start = System.currentTimeMillis();
			this.first = false;
		}
		
		double r = 1.0;
		if (verbose > 0.0) r = Math.random();

		if (r < verbose)
			System.out.println("Photons: " + this.photons.size());
		
		Iterator<Object[]> itr = this.photons.iterator();
		
		boolean o = true;
		
		double delta = PhysicalConstants.C * s;

		i: while (itr.hasNext()) {
			Object[] p = itr.next();
			
			((Vector) p[0]).addTo(((Vector) p[1]).multiply(delta));
			
			if (((double[]) p[3])[0] > 0.0) ((double[]) p[3])[0] -= delta;
			
			if (trace && ((double[]) p[3])[0] > this.lifetime) {
				itr.remove();
				continue i;
			}
			
			if (o && r < verbose) {
				System.out.println("PhotonMoved: " + ((Vector) p[1]).length() * delta);
				o = false;
			}
			
			double dist = 0.0;
			
			if (this.trace && ((double[]) p[3])[0] < 0.0 && this.absorber instanceof AbsorberSet)
				dist = ((AbsorberSet) this.absorber).getDistance((Vector) p[0], (Vector) p[1]);
			
			if (r < verbose)
				System.out.println("DefaultPhotonField: Distance = " + dist);
			
			((double[]) p[3])[0] = dist;
			
			if (this.absorber instanceof AbsorberSet &&
					((Vector) p[0]).length() >
						((AbsorberSet) this.absorber).getMaxProximity()) {
					itr.remove();
			} else if ((!trace || ((double[]) p[3])[0] <= delta) &&
						this.absorber.absorb((Vector) p[0], (Vector) p[1], ((double[]) p[2])[0])) {
				itr.remove();
			}
		}
		
		double next;

		w: while ((next = this.absorber.getNextEmit()) < s) {
			if (r < verbose)
				System.out.println("Next Emit: " + next);

			double d = this.absorber.getEmitEnergy();
			Evaluable<PackedCollection> x = this.absorber.getEmitPosition().get();
			Vector y = new Vector(this.absorber.emit().get().evaluate(), 0);
			
			if (x == null) {
				System.out.println("DefaultPhotonField: " + this.absorber +
									" returned null emit position.");
				continue w;
			}
			
			if (y == null) {
				System.out.println("DefaultPhotonField: " + this.absorber +
									" returned null emit direction.");
				continue w;
			}
			
			if (checkLength) {
				double l = y.length();
				if (l > 1.0 + ep || l < 1.0 - ep)
					System.out.println("DefaultPhotonField: Length was " + l);
			}
			
			if (this.trace)
				photons.add(new Object[] { x.evaluate(new Object[0]), y, new double[] { d }, new double[] { -1.0 }});
			else
				photons.add(new Object[] { x.evaluate(new Object[0]), y, new double[] { d }, new double[] { 0.0 }});
		}
		
		this.tot++;
		
		if (this.log > 0 && this.tot % this.log == 0) {
			double rate = (System.currentTimeMillis() - this.start) /
							(60 * 60000 * this.clock.getTime());
			this.timeChart.addEntry(rate);
			this.costChart.addEntry(rate * 0.0928);
			
			this.sizeChart.addEntry(this.getSize());
			
			if (this.file != null) {
				StringBuilder b = new StringBuilder();
				this.sizeChart.print(b);
				
				try (BufferedWriter out = new BufferedWriter(new FileWriter(this.file))) {
					out.write(b.toString());
				} catch (IOException e) {
					System.out.println("DefaultPhotonField: " + e.getMessage());
				}
			}
		}
	}
}
