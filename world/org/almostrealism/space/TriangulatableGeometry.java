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

import org.almostrealism.graph.Mesh;
import org.almostrealism.graph.Triangulatable;

/**
 * @author  Michael Murray
 */
public class TriangulatableGeometry extends GeometryStack implements Triangulatable {
	
	/**
	 * @return  A Mesh object with location, size, scale coefficients,
	 *          rotation coefficients, and transformations as this
	 *          {@link BasicGeometry}.
	 */
	@Override
	public Mesh triangulate() {
		Mesh m = new Mesh();
		
		m.setLocation(this.getLocation());
		m.setSize(this.getSize());
		m.setScaleCoefficients(this.scaleX, this.scaleY, this.scaleZ);
		m.setRotationCoefficients(this.rotateX, this.rotateY, this.rotateZ);
		m.setTransforms(this.getTransforms());
		
		return m;
	}
}
