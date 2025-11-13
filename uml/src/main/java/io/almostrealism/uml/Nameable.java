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

/**
 * An interface for objects that have a mutable string-based name identifier.
 *
 * <p>This interface extends {@link Named} to add the ability to change an object's name
 * after construction. It is used for objects whose names may need to be updated based on
 * configuration, user input, or runtime conditions.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code Nameable} is designed for objects that:</p>
 * <ul>
 *   <li><strong>User-Configurable Names:</strong> Elements whose names can be set by users</li>
 *   <li><strong>Dynamic Naming:</strong> Objects whose names are determined at runtime</li>
 *   <li><strong>Name Rebinding:</strong> Components that may be renamed or reorganized</li>
 *   <li><strong>Builder Patterns:</strong> Objects constructed incrementally with name assignment</li>
 * </ul>
 *
 * <h2>Relationship with Named</h2>
 * <table>
 *   <tr>
 *     <th>Named</th>
 *     <th>Nameable</th>
 *   </tr>
 *   <tr>
 *     <td>Read-only access via getName()</td>
 *     <td>Adds write access via setName(String)</td>
 *   </tr>
 *   <tr>
 *     <td>Suitable for immutable names</td>
 *     <td>Suitable for mutable names</td>
 *   </tr>
 *   <tr>
 *     <td>Name typically set in constructor</td>
 *     <td>Name can be changed post-construction</td>
 *   </tr>
 * </table>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Basic implementation:</strong></p>
 * <pre>{@code
 * public class ConfigurableOperation implements Nameable {
 *     private String name;
 *
 *     @Override
 *     public String getName() {
 *         return name;
 *     }
 *
 *     @Override
 *     public void setName(String name) {
 *         this.name = name;
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Builder pattern:</strong></p>
 * <pre>{@code
 * ConfigurableOperation op = new ConfigurableOperation();
 * op.setName("my-operation");
 * op.setParameter("timeout", 5000);
 * op.execute();
 * }</pre>
 *
 * <p><strong>User configuration:</strong></p>
 * <pre>{@code
 * Nameable component = createComponent();
 * String userProvidedName = getUserInput();
 * component.setName(userProvidedName);
 * registry.register(component);
 * }</pre>
 *
 * <p><strong>Dynamic renaming:</strong></p>
 * <pre>{@code
 * Nameable task = getTask("old-name");
 * if (task != null) {
 *     task.setName("new-name");
 *     taskManager.update(task);
 * }
 * }</pre>
 *
 * <p><strong>Immutable vs Mutable naming:</strong></p>
 * <pre>{@code
 * // Immutable name (use Named)
 * public class FixedOperation implements Named {
 *     private final String name;
 *     public FixedOperation(String name) { this.name = name; }
 *     public String getName() { return name; }
 * }
 *
 * // Mutable name (use Nameable)
 * public class FlexibleOperation implements Nameable {
 *     private String name;
 *     public String getName() { return name; }
 *     public void setName(String name) { this.name = name; }
 * }
 * }</pre>
 *
 * @see Named
 */
public interface Nameable extends Named {
	/**
	 * Sets the name of this object.
	 *
	 * <p>Implementations should store the provided name and make it available
	 * via {@link #getName()}. The name should typically be a non-null, meaningful
	 * identifier, though the specific requirements depend on the use case.</p>
	 *
	 * <p><strong>Implementation Considerations:</strong></p>
	 * <ul>
	 *   <li>Decide whether to allow null names (typically not recommended)</li>
	 *   <li>Consider validation (e.g., non-empty, valid characters)</li>
	 *   <li>Document any naming constraints or conventions</li>
	 *   <li>Consider thread-safety if names may be changed concurrently</li>
	 * </ul>
	 *
	 * @param name The new name for this object
	 */
	void setName(String name);
}

