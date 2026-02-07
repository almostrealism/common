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

package io.almostrealism.query;

import io.almostrealism.persist.CascadingQuery;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A Query produces an object from any structured data in any database.
 * 
 * @author  Michael Murray
 */
public interface Query<D, K, V> {
	/**
	 * This method may be called by multiple threads simultaneously.
	 * @param cascades TODO
	 * 
	 * @throws InvocationTargetException 
	 * @throws IllegalAccessException
	 */
	Collection<V> execute(D database, K arguments, Map<Class, List<CascadingQuery>> cascades) throws IllegalAccessException, InvocationTargetException;
}
