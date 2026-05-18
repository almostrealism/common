/*
 * Copyright 2025 Michael Murray
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

package io.almostrealism.profile;

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.compute.Process;
import org.almostrealism.io.Describable;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * An interface for objects that represent computational operations and carry
 * associated metadata and compute requirements.
 *
 * <p>This interface extends {@link Describable} to provide a standard way to
 * access operation metadata, compute requirements, and display information
 * for any computational operation in the system.</p>
 *
 * <p>The interface provides both instance methods and static utility methods:</p>
 * <ul>
 *   <li>{@link #getMetadata()} - Returns the operation's metadata</li>
 *   <li>{@link #getComputeRequirements()} - Returns hardware requirements</li>
 *   <li>Static methods for safely extracting names and metadata from arbitrary objects</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * if (operation instanceof OperationInfo) {
 *     OperationMetadata meta = ((OperationInfo) operation).getMetadata();
 *     System.out.println("Operation: " + meta.getDisplayName());
 * }
 *
 * // Or use static utility methods
 * String name = OperationInfo.name(someObject);
 * }</pre>
 *
 * @see OperationMetadata
 * @see ComputeRequirement
 * @see Describable
 *
 * @author Michael Murray
 */
public interface OperationInfo extends Describable {

	/**
	 * Returns the metadata associated with this operation.
	 *
	 * <p>The metadata contains information such as the operation's name,
	 * description, unique identifier, and hierarchical relationships
	 * with other operations.</p>
	 *
	 * @return the {@link OperationMetadata} for this operation, or {@code null}
	 *         if no metadata is available
	 */
	OperationMetadata getMetadata();

	/**
	 * Returns the compute requirements for this operation.
	 *
	 * <p>Compute requirements specify hardware capabilities or constraints
	 * needed to execute this operation, such as GPU availability, memory
	 * requirements, or specific hardware backends.</p>
	 *
	 * @return a list of {@link ComputeRequirement}s, or {@code null} if
	 *         no specific requirements are needed (default implementation)
	 */
	default List<ComputeRequirement> getComputeRequirements() {
		return null;
	}

	/**
	 * Extracts the display name from a value if it implements {@link OperationInfo}.
	 *
	 * <p>This utility method safely handles any object type:</p>
	 * <ul>
	 *   <li>If the value is an {@link OperationInfo} with metadata, returns the display name</li>
	 *   <li>Otherwise, returns {@link String#valueOf(Object)}</li>
	 * </ul>
	 *
	 * @param <T>   the type of the value
	 * @param value the value to extract the name from
	 * @return the display name if available, otherwise the string representation
	 */
	static <T> String name(T value) {
		if (value instanceof OperationInfo) {
			if (((OperationInfo) value).getMetadata() == null) {
				return String.valueOf(value);
			}

			return ((OperationInfo) value).getMetadata().getDisplayName();
		} else {
			return String.valueOf(value);
		}
	}

	/**
	 * Extracts the display name with unique identifier from a value if it
	 * implements {@link OperationInfo}.
	 *
	 * <p>The returned format is "displayName:id", which is useful for
	 * distinguishing between operations with the same name.</p>
	 *
	 * @param <T>   the type of the value
	 * @param value the value to extract the name and id from
	 * @return the display name with id if available, otherwise the string representation
	 */
	static <T> String nameWithId(T value) {
		if (value instanceof OperationInfo) {
			if (((OperationInfo) value).getMetadata() == null) {
				return String.valueOf(value);
			}

			return ((OperationInfo) value).getMetadata().getDisplayName() + ":" +
					((OperationInfo) value).getMetadata().getId();
		} else {
			return String.valueOf(value);
		}
	}

	/**
	 * Extracts the short description from a value if it implements {@link OperationInfo}.
	 *
	 * <p>This is useful for displaying brief information about an operation
	 * in user interfaces or log messages.</p>
	 *
	 * @param <T>   the type of the value
	 * @param value the value to extract the description from
	 * @return the short description if available, otherwise the string representation
	 */
	static <T> String display(T value) {
		if (value instanceof OperationInfo) {
			return ((OperationInfo) value).getMetadata().getShortDescription();
		} else {
			return String.valueOf(value);
		}
	}

	/**
	 * Extracts the {@link OperationMetadata} from a value if it implements
	 * {@link OperationInfo}.
	 *
	 * @param <T>   the type of the value
	 * @param value the value to extract metadata from
	 * @return the {@link OperationMetadata} if available, otherwise {@code null}
	 */
	static <T> OperationMetadata metadataForValue(T value) {
		if (value instanceof OperationInfo) {
			return ((OperationInfo) value).getMetadata();
		} else {
			return null;
		}
	}

	/**
	 * Creates a new {@link OperationMetadata} for a process by combining the
	 * provided metadata with metadata from all child operations.
	 *
	 * <p>This method collects metadata from all children of the process that
	 * implement {@link OperationInfo} and creates a hierarchical metadata
	 * structure.</p>
	 *
	 * @param <P>      the process type
	 * @param <T>      the result type of the process
	 * @param process  the process whose children will be examined
	 * @param metadata the parent metadata to combine with child metadata
	 * @return a new {@link OperationMetadata} containing the parent metadata
	 *         and all collected child metadata
	 */
	static <P extends Process<?, ?>, T> OperationMetadata metadataForProcess(
						Process<P, T> process, OperationMetadata metadata) {
		List<OperationMetadata> children = process.getChildren().stream()
				.filter(v -> v instanceof OperationInfo)
				.map(v -> ((OperationInfo) v).getMetadata())
				.filter(Objects::nonNull).collect(Collectors.toList());
		return new OperationMetadata(metadata, children);
	}
}
