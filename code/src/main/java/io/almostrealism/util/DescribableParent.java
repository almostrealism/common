/*
 * Copyright 2024 Michael Murray
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
package io.almostrealism.util;

import io.almostrealism.relation.Parent;
import org.almostrealism.io.Describable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * An interface that combines {@link Parent} and {@link Describable} to provide
 * hierarchical description capabilities.
 *
 * <p>This interface is designed for tree-structured objects where each node
 * has children and can generate a description that incorporates descriptions
 * from all its descendants. It enables recursive description generation
 * that reflects the structure of the object hierarchy.</p>
 *
 * <p>The description mechanism works as follows:</p>
 * <ol>
 *   <li>{@link #describe()} delegates to {@link #description()}</li>
 *   <li>{@link #description()} collects descriptions from all children</li>
 *   <li>{@link #description(List)} formats the collected descriptions
 *       (must be overridden by implementations)</li>
 * </ol>
 *
 * <p>Example implementation:</p>
 * <pre>{@code
 * public class TreeNode implements DescribableParent<TreeNode> {
 *     private List<TreeNode> children;
 *     private String name;
 *
 *     public Collection<TreeNode> getChildren() {
 *         return children;
 *     }
 *
 *     public String description(List<String> childDescriptions) {
 *         return name + "[" + String.join(", ", childDescriptions) + "]";
 *     }
 * }
 * }</pre>
 *
 * @param <T> the type of child elements
 *
 * @see Parent
 * @see Describable
 *
 * @author Michael Murray
 */
public interface DescribableParent<T> extends Parent<T>, Describable {

	/**
	 * {@inheritDoc}
	 *
	 * <p>Delegates to {@link #description()} to generate a hierarchical
	 * description including all children.</p>
	 *
	 * @return a description of this object and its descendants
	 */
	@Override
	default String describe() {
		return description();
	}

	/**
	 * Generates a description of this object by collecting and formatting
	 * descriptions from all children.
	 *
	 * <p>This method collects descriptions from all child elements and
	 * passes them to {@link #description(List)} for formatting. If there
	 * are no children, an empty list is passed.</p>
	 *
	 * @return a formatted description incorporating all child descriptions
	 */
	default String description() {
		Collection<T> children = getChildren();
		if (children == null) children = Collections.emptyList();

		return description(children.stream()
				.map(DescribableParent::description)
				.collect(Collectors.toList()));
	}

	/**
	 * Formats the collected child descriptions into a final description string.
	 *
	 * <p>Implementations must override this method to define how child
	 * descriptions are combined into the parent's description.</p>
	 *
	 * @param children a list of description strings from child elements
	 * @return the formatted description
	 * @throws UnsupportedOperationException if not overridden (default behavior)
	 */
	default String description(List<String> children) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Extracts a description from an arbitrary object.
	 *
	 * <p>This utility method handles different object types:</p>
	 * <ul>
	 *   <li>If {@code null}, returns {@code null}</li>
	 *   <li>If {@link DescribableParent}, calls {@link #description()}</li>
	 *   <li>If {@link Describable}, calls {@link Describable#describe()}</li>
	 *   <li>Otherwise, calls {@link Object#toString()}</li>
	 * </ul>
	 *
	 * @param o the object to describe
	 * @return the description string, or {@code null} if the object is {@code null}
	 */
	static String description(Object o) {
		if (o == null)
			return null;

		if (o instanceof DescribableParent) {
			return ((DescribableParent<?>) o).description();
		}

		return o instanceof Describable ? ((Describable) o).describe() : o.toString();
	}
}
