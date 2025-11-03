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

package org.almostrealism.physics;

import org.almostrealism.color.RGBFeatures;
import org.almostrealism.geometry.DiscreteField;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.RGB;
import org.almostrealism.color.Shader;
import org.almostrealism.color.ShaderContext;

import org.almostrealism.geometry.RayFeatures;
import io.almostrealism.relation.Producer;
import org.almostrealism.color.LightingContext;

/**
 * A {@link RigidBodyStateShader} can be used to modify the display of other shaders based on a property
 * of the state of a RigidBody object. A RigidBodyStateShader modifies the light direction and intensity
 * based on the direction and intensity of either the velocity or force experienced by a rigid body.
 * 
 * @author  Michael Murray
 */
public class RigidBodyStateShader<T extends ShaderContext> implements Shader<T>, RGBFeatures, RayFeatures {
	public static final int VELOCITY = 1;
	public static final int FORCE = 2;
	
	private int type;
	private double min, max;
	private Shader<T> shader;
	
	/**
	 * Constructs a new RigidBodyStateShader object that shades based on the
	 * state property specified by the integer type code.
	 * 
	 * @param type  Integer type code.
	 * @param min  Minimum value of state property.
	 * @param max  Maximum value of state property.
	 * @param s  Shader instance to use for shading.
	 */
	public RigidBodyStateShader(int type, double min, double max, Shader<T> s) {
		if (type > 2 || type < 1) throw new IllegalArgumentException("Invalid type code: " + type);
		
		this.type = type;
		
		this.min = min;
		this.max = max;
		
		this.shader = s;
	}
	
	/**
	 * @return  The integer type code for this RigidBodyStateShader object.
	 */
	public int getType() { return this.type; }
	
	/**
	 * @return  The Shader object stored by this RigidBodyStateShader object.
	 */
	public Shader<T> getShader() { return this.shader; }
	
	/**
	 * @see org.almostrealism.color.Shader#shade(LightingContext, DiscreteField)
	 */
	@Override
	public Producer<RGB> shade(T p, DiscreteField f) {
		if (p.getSurface() instanceof RigidBody == false)
			return white();
		
		RigidBody.State state = ((RigidBody) p.getSurface()).getState();
		
		Vector d;
		
		if (this.type == RigidBodyStateShader.VELOCITY)
			d = state.getLinearVelocity();
		else
			d = state.getForce();
		
		double m = (d.length() - this.min) / (this.max - this.min);
		if (m < 0.0) m = 0.0;
		if (m > 1.0) m = 1.0;
		
		d.divideBy(d.length());
		p.setLightDirection(v(d));
		
		return multiply(rgb(m), shader.shade(p, f));
	}
}
