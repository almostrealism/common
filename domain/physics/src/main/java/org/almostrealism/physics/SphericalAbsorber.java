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

/*
 * Copyright (C) 2006  Mike Murray
 *
 *  All rights reserved.
 *  This document may not be reused without
 *  express written permission from Mike Murray.
 */

package org.almostrealism.physics;

import org.almostrealism.electrostatic.PotentialMap;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * A {@link SphericalAbsorber} instance represents an atomic component that absorbs and emits
 * energy within a spherical volume of space. A spherical absorber stores a displacement
 * vector which represents the direction and magnitude of the absorbers displacement from
 * equalibrium (where displacement vector = origin = (0.0, 0.0, 0.0). A spherical absorber
 * will be initialized with a reference to a {@link PotentialMap} object which provides
 * a value for the potential energy at each point within the sphere. The energy stored
 * by the absorber should be equal to the potential energy given by the potential map for
 * the displacement vector. The center of the spherical volume is assumed to be the origin
 * of the absorbers internal coordinate system. When referencing the PotentialMap object,
 * a vector of unit length represents a point on the surface of the sphere (shorter
 * than unit length is inside the sphere, greater than unit length is outside the sphere).
 * 
 * @author  Michael Murray
 */
public interface SphericalAbsorber extends Absorber {
	/**
	 * @param m  The PotentialMap instance for this spherical absorber to use.
	 */
	void setPotentialMap(PotentialMap m);
	
	/**
	 * @return  The PotentialMap instance used by this spherical absorber.
	 */
	PotentialMap getPotentialMap();
	
	/**
	 * @param r  The radius of the spherical volume. (Usually measured in micrometers).
	 */
	void setRadius(double r);
	
	/**
	 * @return  The radius of the spherical volume. (Usually measured in micrometers).
	 */
	double getRadius();
	
	/**
	 * @return  {x, y, z} - The displacement vector for this spherical aborber. A unit length
	 *          vector represents a displacement equal to the radius of this spherical absorber.
	 */
	Producer<PackedCollection> getDisplacement();
}
