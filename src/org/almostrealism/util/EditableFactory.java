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

/** 
 * EditableFactory is the parent class for classes that can be used to construct
 * Editable objects of some type.
 */
@Deprecated
public abstract class EditableFactory {
	/**
	  Returns an array of String objects containing names for each type of Editable object
	  this EditableFactory implementation can construct. The names must be in the array
	  in the same order as the object indices they represent. This method must be implemented
	  by classes that extend EditableFactory.
	*/
	
	public abstract String[] getTypeNames();
	
	/**
	  Constructs an Editable object of the type specified by the integer index.
	  This method must be implemented by classes that extend EditableFactory.
	*/
	
	public abstract Editable constructObject(int index);
}
