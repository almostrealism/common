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

package org.almostrealism.color;

import java.util.HashSet;
import java.util.Iterator;

import org.almostrealism.geometry.DiscreteField;
import org.almostrealism.color.computations.RGBAdd;
import io.almostrealism.relation.Producer;

/**
 * @author  Michael Murray
 */
public class ShaderSet<C extends LightingContext> extends HashSet<Shader<C>> implements Shader<C> {
    /**
     * @return  The sum of the values given by the shade method for each {@link Shader}
	 *          instance stored by this {@link ShaderSet}.
     */
    @Override
    public Producer<RGB> shade(C p, DiscreteField normals) {
        Producer<RGB> color = null;
        
        Iterator<Shader<C>> itr = super.iterator();

        while (itr.hasNext()) {
        	if (color == null) {
        		color = itr.next().shade(p, normals);
			} else {
        		final Producer<RGB> fc = color;
				color = () -> new RGBAdd(fc, itr.next().shade(p, normals));
			}
		}
        
        return color;
    }
    
	/** @return  False. */
	@Override
	public boolean equals(Object o) { return false; }
	
	/** @return  "ShaderSet". */
	@Override
	public String toString() { return "ShaderSet[" + size() + "]"; }
}
