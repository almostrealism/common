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
import org.almostrealism.algebra.VectorMath;
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

// TODO  Consider creating a custom list for photon set (tick creates many many double[][]).
public class DefaultPhotonField implements PhotonField {
	public static double verbose = 0.01;
	public static boolean checkLength = true;
	public static double ep = Math.pow(10.0, -10.0);
	
	private Clock clock;
	private final Set<Object[]> photons;
	private Absorber absorber;
	private long delta = 1;
	private double lifetime = Double.MAX_VALUE - 1.0;
	private boolean trace = true;
	
	private final Chart sizeChart;
	private final Chart timeChart;
	private final Chart costChart;
	private long tot = 0, log = 500;
	private boolean first;
	private long start;
	private String file;
	
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
		Iterator itr = this.photons.iterator();
		double e = 0.0;
		
		while (itr.hasNext()) {
			double[][] p = (double[][]) itr.next();
			
			if (VectorMath.length(VectorMath.subtract(p[0], x)) < radius)
				e += p[2][0];
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
		// TODO  Implement removePhotons method.
		return 0;
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
				StringBuffer b = new StringBuffer();
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
