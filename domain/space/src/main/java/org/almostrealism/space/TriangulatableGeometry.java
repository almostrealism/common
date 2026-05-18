/*
 * Copyright 2017 Michael Murray
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

package org.almostrealism.space;

/**
 * A concrete geometry class that combines {@link GeometryStack} with {@link Triangulatable}
 * to provide a base for 3D objects that can be converted to triangle mesh representations.
 *
 * <p>When {@link #triangulate()} is called, a new {@link Mesh} is created that inherits
 * the current geometry settings (location, size, scale, rotation, and transforms) from
 * this object, providing a starting point for further mesh construction by subclasses.
 *
 * @author  Michael Murray
 * @see GeometryStack
 * @see Triangulatable
 * @see Mesh
 */
public class TriangulatableGeometry extends GeometryStack implements Triangulatable {
	
	/**
	 * @return  A Mesh object with location, size, scale coefficients,
	 *          rotation coefficients, and transformations as this
	 *          {@link TriangulatableGeometry}.
	 */
	@Override
	public Mesh triangulate() {
		Mesh m = new Mesh();
		
		m.setLocation(this.getLocation());
		m.setSize(this.getSize());
		m.setScaleCoefficients(scale.getX(), scale.getY(), scale.getZ());
		m.setRotationCoefficients(this.rotateX, this.rotateY, this.rotateZ);
		m.setTransforms(this.getTransforms());
		
		return m;
	}
}
