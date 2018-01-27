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

package org.almostrealism.graph.io;


import java.beans.DefaultPersistenceDelegate;
import java.beans.Encoder;
import java.beans.Statement;

import com.almostrealism.lighting.PointLight;

/**
 * A LightPersistenceDelegate object adjusts the way the a Light object is encoded into XML
 * when using an XMLEncoder.
 */
public class LightPersistenceDelegate extends DefaultPersistenceDelegate {
	/** Properly encodes a Light object. */
	public void initialize(Class type, Object oldInstance, Object newInstance, Encoder out) {
		super.initialize(type, oldInstance, newInstance, out);
		
		if (oldInstance instanceof PointLight) {
			double a[] = ((PointLight) oldInstance).getAttenuationCoefficients();
			
			out.writeStatement(new Statement(oldInstance, "setAttenuationCoefficients",
						new Object[] {new Double(a[0]), new Double(a[1]), new Double(a[2])}));
		}
	}
}
