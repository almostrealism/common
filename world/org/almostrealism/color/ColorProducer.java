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

package org.almostrealism.color;

import org.almostrealism.algebra.TripleFunction;
import org.almostrealism.uml.Function;
import org.almostrealism.util.Producer;

/**
 * ColorProducer is implemented by any class that can produce an RGB object
 * given some array of input objects.
 * 
 * @author Mike Murray
 */
@Function
public interface ColorProducer extends Producer<RGB>, TripleFunction<RGB> {
    /**
     * Produces a color using the specified arguments.
     * 
     * TODO  Uses generics for the type of arguments.
     * 
     * @param args  Arguments.
     * @return  The RGB color produced.
     */
    RGB evaluate(Object args[]);
}
