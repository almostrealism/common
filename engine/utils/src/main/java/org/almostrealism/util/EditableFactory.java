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

package org.almostrealism.util;

import io.almostrealism.relation.Editable;

/**
 * Abstract factory for constructing {@link Editable} objects of various types.
 *
 * <p>Subclasses implement this factory to provide a registry of editable object types
 * that can be created by index. The type names array provides human-readable names
 * for each type index.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class ShapeFactory extends EditableFactory {
 *     public String[] getTypeNames() {
 *         return new String[] { "Circle", "Rectangle", "Triangle" };
 *     }
 *
 *     public Editable constructObject(int index) {
 *         switch (index) {
 *             case 0: return new Circle();
 *             case 1: return new Rectangle();
 *             case 2: return new Triangle();
 *             default: return null;
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author Michael Murray
 * @deprecated Use more flexible factory patterns or dependency injection instead.
 */
@Deprecated
public abstract class EditableFactory {

	/**
	 * Returns the names of all editable object types this factory can construct.
	 * The names correspond to indices passed to {@link #constructObject(int)}.
	 *
	 * @return an array of type names in index order
	 */
	public abstract String[] getTypeNames();

	/**
	 * Constructs an editable object of the type specified by the index.
	 *
	 * @param index the type index (must be valid for {@link #getTypeNames()})
	 * @return a new Editable instance of the specified type
	 */
	public abstract Editable constructObject(int index);
}
