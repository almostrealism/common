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

package io.almostrealism.relation;

/**
 * An interface for objects with runtime-modifiable properties.
 *
 * <p>{@link Editable} provides a reflection-like API for accessing and modifying
 * object properties at runtime. This enables generic property editors, UI bindings,
 * and dynamic configuration without compile-time knowledge of the specific type.</p>
 *
 * <h2>Property Access</h2>
 * <p>Properties are accessed by index, with metadata available through:</p>
 * <ul>
 *   <li>{@link #getPropertyNames()} - Human-readable names</li>
 *   <li>{@link #getPropertyDescriptions()} - Detailed descriptions</li>
 *   <li>{@link #getPropertyTypes()} - Value types for each property</li>
 *   <li>{@link #getPropertyValues()} - Current values</li>
 * </ul>
 *
 * <h2>Input Properties</h2>
 * <p>Some properties are computed repeatedly through {@link Producer}s. These
 * "input properties" can be accessed and modified separately through:</p>
 * <ul>
 *   <li>{@link #getInputPropertyValues()} - Get current producer inputs</li>
 *   <li>{@link #setInputPropertyValue(int, Producer)} - Set a producer input</li>
 * </ul>
 *
 * <h2>Selection Type</h2>
 * <p>The nested {@link Selection} class represents a property with a fixed
 * set of valid options, useful for enum-like properties.</p>
 *
 * @see Producer
 *
 * @author Michael Murray
 */
public interface Editable {
    /**
     * An Editable.Selection object stores a set of options and a selection.
     */
    class Selection {
        private String options[];
        private int selected;
        
        public Selection(String options[]) {
            this.options = options;
            this.selected = 0;
        }
        
        public String[] getOptions() { return this.options; }
        public void setSelected(int index) { this.selected = index; }
        public int getSelected() { return this.selected; }
        
        public String toString() { return this.options[this.selected]; }
    }
    
	/**
	 * Returns an array of String objects with names for each editable property
	 * of this Editable object.
	 */
	String[] getPropertyNames();
	
	/**
	 * Returns an array of String objects with descriptions for each editable property
	 * of this Editable object.
	 */
	String[] getPropertyDescriptions();
	
	/**
	 * Returns an array of Class objects representing the class types of each editable
	 * property of this Editable object.
	 */
	Class[] getPropertyTypes();
	
	/**
	 * Returns the values of the properties of this Editable object as an Object array.
	 */
	Object[] getPropertyValues();
	
	/**
	 * Sets the value of the property of this Editable object at the specified index
	 * to the specified value.
	 */
	void setPropertyValue(Object value, int index);
	
	/**
	 * Sets the values of properties of this Editable object to those specified.
	 */
	void setPropertyValues(Object values[]);
	
	/**
	 * @return  An array of Producer objects containing the property values of those
	 *          properties that are repeatedly evaluated.
	 */
	Producer[] getInputPropertyValues();
	
	/**
	 * @param index  Index of input property (array index from this.getInputPropertyValue).
	 * @param p  Producer object to use for input property.
	 */
	void setInputPropertyValue(int index, Producer p);
}
