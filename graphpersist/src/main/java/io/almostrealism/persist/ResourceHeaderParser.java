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
 * Copyright (C) 2007  Mike Murray
 *
 *  All rights reserved.
 *  This document may not be reused without
 *  express written permission from Mike Murray.
 *
 */

package io.almostrealism.persist;

/**
 * The ResourceHeaderParser interface is implemented by classes that provide
 * methods for identifying if the header (first chunk of bytes from DB) of
 * a DistributedResource indicates that the resource should be represented
 * by a class other than DistributedResource.
 */
public interface ResourceHeaderParser {
	/**
	 * Tests if the resource header indicates that the resource should be
	 * represented by a separate class.
	 * 
	 * @param head  Header for resource.
	 * @return  True if the header matches, false otherwise.
	 */
	boolean doesHeaderMatch(byte[] head);
	
	/**
	 * Returns the resource class to be used if the header matches.
	 * This must be a subclass of DistributedResource.
	 * 
	 * @return  The resource class to be used.
	 */
	Class getResourceClass();
}
