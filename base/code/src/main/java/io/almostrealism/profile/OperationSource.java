/*
 * Copyright 2025 Michael Murray
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

package io.almostrealism.profile;

import java.util.List;
import java.util.Objects;

/**
 * Represents the compiled source code for a single operation, along with
 * metadata about its arguments.
 *
 * <p>{@code OperationSource} captures the generated code (e.g., C, OpenCL, or
 * Metal source) that was produced during compilation of an operation, together
 * with the metadata keys and display names of the operation's input arguments.
 * This information is recorded by
 * {@link OperationProfileNode#recordCompilation} and stored in the profile
 * tree for later inspection by profiling tools.</p>
 *
 * <p>Two {@code OperationSource} instances are considered equal if they have
 * the same source code, argument keys, and argument names. This equality is
 * used to detect recompilation of the same operation.</p>
 *
 * @see OperationProfileNode#recordCompilation
 * @see OperationProfileNode#getOperationSources()
 *
 * @author Michael Murray
 */
public class OperationSource {
	/** The source code string for the operation. */
	private String source;

	/** Unique keys identifying each argument position. */
	private List<String> argumentKeys;

	/** Human-readable names for each argument. */
	private List<String> argumentNames;

	/** Default constructor for deserialization. */
	public OperationSource() { }

	/**
	 * Creates an operation source with the given code and argument information.
	 *
	 * @param source        the generated source code
	 * @param argumentKeys  the metadata keys of the operation's arguments (may be {@code null})
	 * @param argumentNames the display names of the operation's arguments (may be {@code null})
	 */
	public OperationSource(String source, List<String> argumentKeys, List<String> argumentNames) {
		this.source = source;
		this.argumentKeys = argumentKeys;
		this.argumentNames = argumentNames;
	}

	/**
	 * Returns the generated source code for this operation.
	 *
	 * @return the source code string
	 */
	public String getSource() {
		return source;
	}

	/**
	 * Sets the generated source code.
	 *
	 * @param source the source code string
	 */
	public void setSource(String source) {
		this.source = source;
	}

	/**
	 * Returns the metadata keys identifying each argument to the operation.
	 *
	 * @return the argument key list, or {@code null}
	 */
	public List<String> getArgumentKeys() {
		return argumentKeys;
	}

	/**
	 * Sets the argument keys.
	 *
	 * @param argumentKeys the argument key list
	 */
	public void setArgumentKeys(List<String> argumentKeys) {
		this.argumentKeys = argumentKeys;
	}

	/**
	 * Returns the display names of each argument to the operation.
	 *
	 * @return the argument name list, or {@code null}
	 */
	public List<String> getArgumentNames() {
		return argumentNames;
	}

	/**
	 * Sets the argument names.
	 *
	 * @param argumentNames the argument name list
	 */
	public void setArgumentNames(List<String> argumentNames) {
		this.argumentNames = argumentNames;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof OperationSource)) return false;
		OperationSource that = (OperationSource) o;
		return Objects.equals(getSource(), that.getSource()) &&
				Objects.equals(getArgumentKeys(), that.getArgumentKeys()) &&
				Objects.equals(getArgumentNames(), that.getArgumentNames());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getSource(), getArgumentKeys(), getArgumentNames());
	}
}
