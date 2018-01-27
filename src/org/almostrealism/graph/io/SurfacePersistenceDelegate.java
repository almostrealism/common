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

package org.almostrealism.graph.io;


import java.beans.DefaultPersistenceDelegate;
import java.beans.Encoder;
import java.beans.Statement;

import org.almostrealism.color.Shader;
import org.almostrealism.graph.Triangle;
import org.almostrealism.space.AbstractSurface;

// TODO  Add more efficient encoding of Mesh (avoid storing so many vector objects = decreased file size)

/**
 * A SurfacePersistenceDelegate object adjusts the way the an AbstractSurface or AbstractSurfaceUI object
 * is encoded into XML when using an XMLEncoder.
 */
public class SurfacePersistenceDelegate extends DefaultPersistenceDelegate {
	/**
	 * Properly encodes an AbstractSurface or AbstractSurfaceUI object.
	 */
	public void initialize(Class type, Object oldInstance, Object newInstance, Encoder out) {
		super.initialize(type, oldInstance, newInstance, out);
		
		System.out.println(newInstance);
		
		AbstractSurface s = null;
		
		if (oldInstance instanceof AbstractSurface) {
			s = (AbstractSurface)oldInstance;
			
			if (s instanceof Triangle) {
				out.writeStatement(new Statement(oldInstance, "setVertices", ((Triangle)s).getVertices()));
			}
			
//			if (s instanceof Mesh) {
//				Object o = ((Mesh)s).encode();
//				
//				if (!(o instanceof Mesh)) {
//					try {
//						this.initialize(o.getClass(), o, o.getClass().newInstance(), out);
//					} catch (InstantiationException e) {
//						e.printStackTrace();
//					} catch (IllegalAccessException e) {
//						e.printStackTrace();
//					}
//					
//					return;
//				}
//			}
			
			out.writeStatement(new Statement(s, "calculateTransform", new Object[0]));
			
			if (s.getShaderSet() != null)
				out.writeStatement(new Statement(s, "setShaders",
								new Object[] {s.getShaderSet().toArray(new Shader[0])}));
//		} else if (oldInstance instanceof AbstractSurfaceUI) {
//			s = (AbstractSurface)((AbstractSurfaceUI)oldInstance).getSurface();
//			
//			out.writeStatement(new Statement(oldInstance, "setSurface",
//					new Object[] {s}));
		} else {
			return;
		}
	}
}
