/*
 * Copyright 2018 Michael Murray
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


package org.almostrealism.chem;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.physics.Absorber;
import org.almostrealism.physics.Clock;
import org.almostrealism.physics.Volume;

import java.util.ArrayList;

/**
 * A {@link SnellAbsorber} is an {@link Absorber} implementation that absorbs and emits photons
 * contained within a three dimensional volume. The photons absorbed are re-emitted
 * based on Snell's law of refraction.
 * 
 * @author  Michael Murray
 */
public class SnellAbsorber implements Absorber, VectorFeatures {
	private Volume<?> volume;
	private Clock clock;
	private final ArrayList<Object[]> queue = new ArrayList<>();
	private final double[] n = {0, 0}; // Refraction values for mediums
		
	// Upon absorption energy of incoming rays is added to the Queue as an array
	// with important information, like angle and energy, so that conservation is correct.
	public boolean absorb(Vector Position, Vector Direction, double Energy) {
		if (!this.volume.inside(v(Position))) return false;
		
		Object[] data = {Position, Direction, new double[] { Energy }};
		queue.add(data);
		return true;
	}

	/*
	 * Snell's law should come into effect in the emit phase, as it
	 * is related to refraction and not absorbtion. However, this means
	 * that the emit fuction requires that the incoming angle (angle of incidence)
	 * and the n values (refraction indices) are known to predict the angle of 
	 * refraction.
	 * 
	 * If the incAngle > asin(n2/n1), then no refraction occurs, and the photon is
	 * reflected at 100% strength.
	 * 
	 * Assumptions: incAngle will not be above 180
	 */
	@Override
	public Producer<PackedCollection> emit() {
		if (queue.isEmpty()) return null;
		
		double[] d;
		Vector normal;
		double alpha;
		// Creating n values for refractive surfaces
		n[0] = 1.00;
		n[1] = 1.0001;
		
		// d is the direction vector
		d = ((double[][])(queue.get(0)))[1];
		
		// Accepts position vector, returns the Normal 
		normal = (Vector) this.volume.getNormalAt(v((Vector) queue.get(0)[0])).get().evaluate();
		
		// resultant = -(p + 2N(p.N)). What is this good for? 
		// double resultant[] = VectorMath.subtract(VectorMath.multiply(n, VectorMath.dot(d, n) * 2), d);
		
		// Snell's Law calculation
		// R.normal = alpha = sqrt(1 - (n[0]^2/n[1]^2) * (1 - d.normal)^2)
		alpha = Math.sqrt(1 - ((n[0] * n[0]) / (n[1] *n[1])) *
				(1 - Math.pow(new Vector(d).dotProduct(normal), 2)));
		
		// R = -(alpha*N) + (sqrt(1-alpha^2))(N x (N x I))
		Vector n = normal;
		Vector R = n.multiply(alpha).multiply(-1).add(n.crossProduct(n.crossProduct(new Vector(d))).multiply(Math.sqrt(1 - ((alpha * alpha)))));
		
		if (Math.random() < 0.0001) {
			System.out.println(R.toString());
		}
		
		this.queue.remove(0);

		R.normalize();
		return v(R);
	}

	// Get and Set methods follow
	@Override
	public void setClock(Clock c) { this.clock = c; }
	@Override
	public Clock getClock() { return this.clock; }
		
	public void setN(double[] incN) {this.n[0] = incN[0]; this.n[1] = incN[1];}
	public double[] getN() {return this.n;}	
	
	public void setVolume(Volume v) { this.volume = v; }
	public Volume getVolume() { return this.volume; }
	
	// Returns energy of next item in the queue
	@Override
	public double getEmitEnergy() {
		return ((double[][])(queue.get(0)))[2][0];
	}
	
	/** Returns confirmation of existence of another item in queue. */
	@Override
	public double getNextEmit() {
		if (!queue.isEmpty())
			return 0.0; // Confirms next item exists
		else
			return Double.MAX_VALUE;
	}

	/** Returns Position vector of next item. */
	@Override
	public Producer<PackedCollection> getEmitPosition() {
		if (!queue.isEmpty()) {
			if (Math.random() < 0.0001)
				System.out.println(queue.get(0)[0].toString());

			return v((Vector) queue.get(0)[0]);
		} else {
			return null;
		}
	}
}
