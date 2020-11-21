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

package org.almostrealism.time;

import java.util.Iterator;
import java.util.Set;

import org.almostrealism.physics.PhotonField;
import org.almostrealism.physics.PhysicalConstants;

import java.util.HashSet;
import java.util.function.Supplier;

/**
 * A Clock object keeps track of time (usually measured in microseconds).
 * The clock keeps track of a set of photon fields that will be notified
 * when a clock tick occurs.
 * 
 * @author  Michael Murray
 */
public class Clock implements Temporal {
	private Set<PhotonField> fields;
	private long ticks;
	private double interval = Math.pow(10.0, -9.0);
	
	/**
	 * Constructs a new Clock object.
	 */
	public Clock() { this.fields = new HashSet(); }
	
	/**
	 * Adds the specified PhotonField instance to this clock. The photon field
	 * will be notified when a clock tick occurs.
	 * 
	 * @param f  PhotonField instance to add.
	 * @return  The total number of photon fields stored by this Clock object.
	 */
	public int addPhotonField(PhotonField f) {
		this.fields.add(f);
		f.setClock(this);
		return this.fields.size();
	}
	
	/**
	 * Removes the specified PhotonField instance from this clock. The photon field
	 * will no longer be notified when a clock tick occurs.
	 * 
	 * @param f  PhotonField instance to remove.
	 * @return  True if the photon field was removed, false otherwise.
	 */
	public boolean removePhotonField(PhotonField f) { return this.fields.remove(f); }
	
	/**
	 * @return  The Set used by this clock to store PhotonField objects.
	 */
	public Set<PhotonField> getPhotonFields() { return this.fields; }

	public void setPhotonFields(Set<PhotonField> f) { this.fields = f; }
	
	/**
	 * @param time  The time for this clock (usually in microseconds).
	 */
	public void setTime(double time) { this.ticks = (long) (time / this.interval); }
	
	/**
	 * @return  The time for this clock (usually in microseconds).
	 */
	public double getTime() { return this.ticks * this.interval; }
	
	/**
	 * @param tick  The interval for one tick of this clock (usually in microseconds).
	 */
	public void setTickInterval(double tick) { this.interval = tick; }
	
	/**
	 * @return  The interval for one tick of this clock (usually in microseconds).
	 */
	public double getTickInterval() { return this.interval; }

	public void setFrequency(Frequency f) {
		// TODO  Set the tick interval based on the specified frequency
	}
	
	/**
	 * @return  The distance traveled by a photon in one tick of this clock,
	 *          usually measured in micrometers. (The same scale of the tick interval
	 *          time is adopted because the speed of light is a ratio of meters and
	 *          seconds. IE, when the tick interval is measured in microseconds,
	 *          multiply the tick interval by the speed of light yields micrometers.
	 *          If the tick interval where measured in another unit, the tick distance
	 *          will be measured in a distance unit carrying the same prefix. Nanoseconds
	 *          yields nanometers, picoseconds yields picometers, milliseconds yields
	 *          millimeters, etc.)
	 */
	public double getTickDistance() { return this.interval * PhysicalConstants.C; }
	
	/**
	 * @return  The number of ticks since the start of this clock. This number is usually
	 *          not as useful as the actual time of the clock (see getTime). Be careful
	 *          when make assumptions about what a "tick" is. It is not a good idea to
	 *          write code (or XML source) that depends on something without a physical
	 *          counterpart.
	 */
	public long getTicks() { return this.ticks; }
	
	/**
	 * Increments the time of this clock by one tick and notifies all photon fields
	 * of the tick event.
	 */
	@Override
	public Supplier<Runnable> tick() {
		return () -> () -> {
			ticks++;
			fields.forEach(f -> f.tick(interval));
		};
	}
}
