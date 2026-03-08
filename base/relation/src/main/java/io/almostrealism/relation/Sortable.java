/*
 * Copyright 2021 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.almostrealism.relation;

/**
 * An interface for types that provide a sorting hint.
 *
 * <p>{@link Sortable} provides a simple mechanism for objects to indicate
 * their preferred sorting order. This is useful when objects need to be
 * sorted without implementing {@link Comparable}.</p>
 *
 * @deprecated This interface is deprecated. Consider using {@link Comparable}
 *             or a custom {@link java.util.Comparator} instead.
 *
 * @author Michael Murray
 */
@Deprecated
public interface Sortable {
	/**
	 * Returns a hint value for sorting.
	 *
	 * <p>Lower values should be sorted before higher values.</p>
	 *
	 * @return the sort hint value
	 */
	int getSortHint();
}
