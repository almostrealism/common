/*
 * Copyright 2023 Michael Murray
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

package io.almostrealism.uml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * An interface for objects that have a string-based name identifier.
 *
 * <p>This interface provides a standardized way to access the name of an object,
 * along with utility methods for name-based operations such as duplicate removal
 * and name extraction. It is commonly used for objects that need to be identified,
 * logged, or organized by name.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code Named} is designed for objects that:</p>
 * <ul>
 *   <li><strong>Need Identification:</strong> Computations, processes, operations that require names</li>
 *   <li><strong>Support Logging/Debugging:</strong> Objects that appear in logs or error messages</li>
 *   <li><strong>Enable User Reference:</strong> Components referenced by users in configurations or UIs</li>
 *   <li><strong>Require Organization:</strong> Elements managed in named collections or registries</li>
 * </ul>
 *
 * <h2>Relationship with Nameable</h2>
 * <ul>
 *   <li><strong>{@link Named}:</strong> Read-only name access via {@link #getName()}</li>
 *   <li><strong>{@link Nameable}:</strong> Extends {@code Named} with {@code setName(String)} for mutable names</li>
 * </ul>
 *
 * <h2>Utility Methods</h2>
 * <p>This interface provides two key static utility methods:</p>
 * <ul>
 *   <li>{@link #nameOf(Object)} - Safe name extraction from any object</li>
 *   <li>{@link #removeDuplicates(List)} - Deduplication based on names</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Basic implementation:</strong></p>
 * <pre>{@code
 * public class Operation implements Named {
 *     private final String name;
 *
 *     public Operation(String name) {
 *         this.name = name;
 *     }
 *
 *     @Override
 *     public String getName() {
 *         return name;
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Safe name extraction:</strong></p>
 * <pre>{@code
 * Object obj = getObject();
 * String name = Named.nameOf(obj);
 * // Returns: object's name if Named, class simple name otherwise, or "null"
 * }</pre>
 *
 * <p><strong>Removing duplicates by name:</strong></p>
 * <pre>{@code
 * List<Operation> operations = Arrays.asList(
 *     new Operation("add"),
 *     new Operation("multiply"),
 *     new Operation("add"),  // Duplicate name
 *     new Operation("subtract")
 * );
 *
 * List<Operation> unique = Named.removeDuplicates(operations);
 * // Result: [add, multiply, subtract] (first occurrence kept)
 * }</pre>
 *
 * <p><strong>Custom duplicate resolution:</strong></p>
 * <pre>{@code
 * List<VersionedOperation> ops = getOperations();
 *
 * // Keep the operation with highest version when names match
 * List<VersionedOperation> latest = Named.removeDuplicates(ops,
 *     (existing, duplicate) ->
 *         existing.getVersion() > duplicate.getVersion() ? existing : duplicate
 * );
 * }</pre>
 *
 * <p><strong>Logging and debugging:</strong></p>
 * <pre>{@code
 * public void execute(Named operation) {
 *     log.info("Executing: " + operation.getName());
 *     // ... execution logic
 * }
 * }</pre>
 *
 * @see Nameable
 */
public interface Named {
	/**
	 * Returns the name of this object.
	 *
	 * <p>Implementations should return a non-null, meaningful identifier for the object.
	 * The name is typically used for logging, debugging, user display, or organization
	 * purposes.</p>
	 *
	 * @return The name of this object, should not be null
	 */
	String getName();

	/**
	 * Safely extracts a name from any object, handling null and non-Named types.
	 *
	 * <p>This utility method provides a robust way to get a string representation
	 * of any object's name:</p>
	 * <ul>
	 *   <li>If {@code named} is {@code null}, returns {@code "null"}</li>
	 *   <li>If {@code named} implements {@link Named} and has a non-null name, returns that name</li>
	 *   <li>Otherwise, returns the class simple name, or {@code "anonymous"} if unavailable</li>
	 * </ul>
	 *
	 * <p><strong>Examples:</strong></p>
	 * <pre>{@code
	 * Named.nameOf(null)                    // "null"
	 * Named.nameOf(new Operation("add"))    // "add"
	 * Named.nameOf(new ArrayList<>())       // "ArrayList"
	 * Named.nameOf(new Object())            // "Object"
	 * }</pre>
	 *
	 * @param <T> The type of the object
	 * @param named The object to extract a name from
	 * @return A non-null string representing the object's name
	 */
	static <T> String nameOf(T named) {
		if (named == null) return "null";

		if (named instanceof Named && ((Named) named).getName() != null) {
			return ((Named) named).getName();
		}

		String name = named.getClass().getSimpleName();
		if (name == null || name.trim().length() <= 0) name = "anonymous";
		return name;
	}

	/**
	 * Removes duplicate {@link Named} objects from a list based on their names,
	 * keeping the first occurrence of each unique name.
	 *
	 * <p>When multiple objects have the same name, the first one encountered is kept
	 * and subsequent duplicates are removed. This is equivalent to calling
	 * {@link #removeDuplicates(List, BiFunction)} with a chooser that always selects
	 * the first occurrence.</p>
	 *
	 * <p>Null elements are filtered out before processing.</p>
	 *
	 * <p><strong>Example:</strong></p>
	 * <pre>{@code
	 * List<Operation> ops = Arrays.asList(
	 *     new Operation("add"),      // Kept
	 *     new Operation("multiply"), // Kept
	 *     new Operation("add"),      // Removed (duplicate)
	 *     null,                      // Filtered out
	 *     new Operation("subtract")  // Kept
	 * );
	 * List<Operation> unique = Named.removeDuplicates(ops);
	 * // Result: [add, multiply, subtract]
	 * }</pre>
	 *
	 * @param <T> The type of {@link Named} objects in the list
	 * @param list The list potentially containing duplicate names
	 * @return A new list with duplicates removed, preserving first occurrences
	 */
	static <T extends Named> List<T> removeDuplicates(List<T> list) {
		return removeDuplicates(list, (a, b) -> a);
	}

	/**
	 * Removes duplicate {@link Named} objects from a list based on their names,
	 * using a custom chooser function to resolve conflicts.
	 *
	 * <p>When multiple objects have the same name, the provided chooser function
	 * is called to determine which object to keep. The chooser receives the currently
	 * selected object and the new duplicate, and returns which one should be retained.</p>
	 *
	 * <p>This method is useful when you need custom logic to decide which duplicate
	 * to keep (e.g., based on version numbers, timestamps, or other criteria).</p>
	 *
	 * <p>Null elements are filtered out before processing. The order of unique names
	 * in the result matches the order of first occurrence in the input list.</p>
	 *
	 * <p><strong>Example - Keep most recent by timestamp:</strong></p>
	 * <pre>{@code
	 * List<VersionedOperation> ops = getOperations();
	 * List<VersionedOperation> latest = Named.removeDuplicates(ops,
	 *     (existing, duplicate) ->
	 *         existing.getTimestamp() > duplicate.getTimestamp() ? existing : duplicate
	 * );
	 * }</pre>
	 *
	 * <p><strong>Example - Keep highest priority:</strong></p>
	 * <pre>{@code
	 * Named.removeDuplicates(tasks,
	 *     (current, newTask) ->
	 *         current.getPriority() > newTask.getPriority() ? current : newTask
	 * );
	 * }</pre>
	 *
	 * @param <T> The type of {@link Named} objects in the list
	 * @param list The list potentially containing duplicate names
	 * @param chooser A function that receives (existing, duplicate) and returns which to keep
	 * @return A new list with duplicates removed using the chooser logic
	 */
	static <T extends Named> List<T> removeDuplicates(List<T> list, BiFunction<T, T, T> chooser) {
		List<T> values = new ArrayList<>();
		list.stream().filter(Objects::nonNull).forEach(values::add);

		List<String> names = new ArrayList<>();
		Map<String, T> chosen = new HashMap<>();

		values.forEach(v -> {
			String name = v.getName();

			if (names.contains(name)) {
				chosen.put(name, chooser.apply(chosen.get(name), v));
			} else {
				names.add(name);
				chosen.put(name, v);
			}
		});

		return names.stream().map(chosen::get).collect(Collectors.toList());
	}
}

