/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;

import java.util.List;

/**
 * A functional interface for listening to and recording compilation timing events.
 *
 * <p>This listener is used to track the performance of code compilation operations,
 * allowing profiling tools to measure how long it takes to compile different
 * operations to native code, OpenCL kernels, or other execution formats.</p>
 *
 * <p>Implementations can use this information for:</p>
 * <ul>
 *   <li>Performance profiling and optimization</li>
 *   <li>Identifying compilation bottlenecks</li>
 *   <li>Caching decisions based on compilation cost</li>
 *   <li>Debugging compilation issues</li>
 * </ul>
 *
 * <p>Example implementation:</p>
 * <pre>{@code
 * CompilationTimingListener listener = (metadata, args, code, nanos) -> {
 *     System.out.printf("Compiled %s in %d ms%n",
 *         metadata.getDisplayName(), nanos / 1_000_000);
 * };
 * }</pre>
 *
 * @see OperationMetadata
 * @see Scope
 *
 * @author Michael Murray
 */
@FunctionalInterface
public interface CompilationTimingListener {

	/**
	 * Records a compilation event using the metadata and arguments from a {@link Scope}.
	 *
	 * <p>This is a convenience method that extracts the metadata and argument
	 * variables from the scope and delegates to
	 * {@link #recordCompilation(OperationMetadata, List, String, long)}.</p>
	 *
	 * @param scope  the scope that was compiled, containing metadata and argument information
	 * @param source the generated source code that was compiled
	 * @param nanos  the compilation time in nanoseconds
	 */
	default void recordCompilation(Scope<?> scope, String source, long nanos) {
		recordCompilation(scope.getMetadata(), scope.getArgumentVariables(), source, nanos);
	}

	/**
	 * Records a compilation timing event with detailed information about the compiled operation.
	 *
	 * <p>This is the primary method that implementations must provide. It receives
	 * complete information about what was compiled and how long it took.</p>
	 *
	 * @param metadata  the metadata describing the operation that was compiled,
	 *                  including name, description, and hierarchical information
	 * @param arguments the list of array variables that serve as arguments to the
	 *                  compiled operation
	 * @param code      the generated source code (e.g., C, OpenCL, or other target language)
	 * @param nanos     the time taken to compile the operation, in nanoseconds
	 */
	void recordCompilation(OperationMetadata metadata, List<ArrayVariable<?>> arguments, String code, long nanos);
}
