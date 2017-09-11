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

/*
 * Copyright (C) 2005-06  Mike Murray
 *
 *  All rights reserved.
 *  This document may not be reused without
 *  express written permission from Mike Murray.
 *
 */

package org.almostrealism.io;

import java.io.OutputStream;

/**
 * An implementation of the Storable interface provides a way to persist the state of
 * an instance. The implementing class must provide a store method that accepts an
 * OutputStream. The implementing class should provide some obvious way to load the
 * state of a stored instance (usually by a static method or constructor).
 * 
 * @author Mike Murray
 */
public interface Storable {
	/**
	 * Persist the contents of this Storable instance.
	 * 
	 * @param out  Stream to write contents to.
	 * @return  True if succesfully stored, false otherwise.
	 */
	boolean store(OutputStream out);
}
