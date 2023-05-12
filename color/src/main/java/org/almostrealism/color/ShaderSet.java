/*
 * Copyright 2023 Michael Murray
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
import io.almostrealism.relation.Producer;

/**
 * @author  Michael Murray
 */
public class ShaderSet<C extends LightingContext> extends HashSet<Shader<C>> implements Shader<C>, RGBFeatures {
    /**
     * @return  The sum of the values given by the shade method for each {@link Shader}
	 *          instance stored by this {@link ShaderSet}.
     */
    @Override
    public Producer<RGB> shade(C p, DiscreteField normals) {
        Iterator<Shader<C>> itr = super.iterator();
		Producer<RGB> colors = null;

        while (itr.hasNext()) {
        	colors = colors == null ? itr.next().shade(p, normals) : add(colors, itr.next().shade(p, normals));
		}

        return colors;
    }
    
	/** @return  False. */
	@Override
	public boolean equals(Object o) { return false; }
	
	/** @return  "ShaderSet". */
	@Override
	public String toString() { return "ShaderSet[" + size() + "]"; }
}
