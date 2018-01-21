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

package org.almostrealism.color;

import java.util.HashSet;
import java.util.Iterator;

import org.almostrealism.algebra.DiscreteField;

/**
 * @author  Michael Murray
 */
public class ShaderSet extends HashSet<Shader> implements Shader {
    /**
     * @return  The sum of the values given by the shade method for each Shader object stored by this ShaderSet object.
     */
    public ColorProducer shade(ShaderContext p, DiscreteField normals) {
        ColorSum color = new ColorSum();
        
        Iterator<Shader> itr = super.iterator();
        while (itr.hasNext()) color.add(itr.next().shade(p, normals));
        
        return color;
    }
    
	/** @return  False. */
	public boolean equals(Object o) { return false; }
	
	/** @return  "ShaderSet". */
	public String toString() { return "ShaderSet"; }
}
