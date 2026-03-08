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

package org.almostrealism.physics;

import org.almostrealism.electrostatic.PotentialMap;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;

import java.util.Iterator;
import java.util.Set;

/**
 * An AbsorberSet instance represents a set of absorbers. An absorber set must keep
 * track of each absorber and provide methods of the Absorber interface that account
 * for the absorption/emission of each absorber in the set.
 * 
 * The contract of the AbsorberSet interface does not require that the
 * implementing class provide the usual acessors of the Set interface
 * return Absorber implementations. It is very likely (as in the case
 * of AbsorberHashSet) that an AbsorberSet implementation extends a
 * standard Set implementation and uses it to store an object which
 * encapsulates an Absorber (AbsorberHashSet.StoredItem).
 * 
 * @author  Michael Murray
 */
public interface AbsorberSet<T> extends Absorber, Set<T> {
	/**
	 * Adds the specified absorber to this absorber set.
	 *
	 * @param a  Absorber instance to add.
	 * @param x  {x, y, z} - Relative position of the absorber.
	 * @return  The total number of absorbers stored by this set.
	 */
	int addAbsorber(Absorber a, Producer x);
	
	/**
	 * Removes the absorbers contained within the specified spherical volume.
	 * 
	 * @param x  {x, y, z} - The center of the spherical volume.
	 * @param radius  The radius of the spherical volume.
	 * @return  The total number of absorbers removed.
	 */
	int removeAbsorbers(double[] x, double radius);
	
	/**
	 * Removes the specified absorber from this set.
	 * 
	 * @param a  Absorber instance to remove.
	 * @return  The total number of absorbers stored by this set.
	 */
	int removeAbsorber(Absorber a);
	
	/**
	 * Returns an iterator for the absorbers contained in this absorber set.
	 */
	Iterator absorberIterator();
	
	/**
	 * @param m  The potential map to use for each absorber in the set.
	 */
	void setPotentialMap(PotentialMap m);
	
	/**
	 * @return  The potential map used for each absorber in the set.
	 */
	PotentialMap getPotentialMap();
	
	/**
	 * @return  The farthest distance from the origin of a this absorber to a point where
	 *          the absorber has nearly zero likelyhood to absorb a photon. This means
	 *          that photons at a distance greater than this radius from the origin
	 *          of this absorber will not be checked for absorption of a photon. If this
	 *          absorber set is used as the absorber for a photon field, the photon field
	 *          should remove photons with position vectors greater in length than this value.
	 */
	double getBound();
	
	/**
	 * Returns the largest decimal value that this AbsorberSet can gaurentee is farther
	 * along the direction vector than the first point that absorption may occur. Assuming
	 * that a photon is located at the specified positon vector and traveling along the
	 * specified direction vector, the photon will not be absorbed before traveling the
	 * distance returned by this method.
	 * 
	 * @param p  The position.
	 * @param d  The direction.
	 * @return  The distance before photon may be absorbed.
	 */
	double getDistance(Vector p, Vector d);
	
	/**
	 * @param radius  The farthest distance from the origin of a given absorber in the set
	 *                to a point where the absorber has nearly zero likelyhood to absorb a photon.
	 *                This means that photons at a distance greater than this radius from the origin
	 *                of an absorber in the set will not be checked for absorption of a photon
	 *                If <= 0.0, all absorbers in the set will always be checked for absorption in
	 *                all cases.
	 */
	void setMaxProximity(double radius);
	
	/**
	 * @return  The farthest distance from the origin of a given absorber in the set
	 *          to a point where the absorber has nearly zero likelyhood to absorb a photon.
	 *          This means that photons at a distance greater than this radius from the origin
	 *          of an absorber in the set will not be checked for absorption of a photon
	 *          If <= 0.0, all absorbers in the set will always be checked for absorption in
	 *          all cases.
	 */
	double getMaxProximity();
}
