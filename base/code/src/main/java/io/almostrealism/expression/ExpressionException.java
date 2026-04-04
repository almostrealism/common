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

package io.almostrealism.expression;

/**
 * An exception thrown when expression tree construction or traversal encounters an error,
 * carrying additional context about the expression depth and node count at the time of failure.
 */
public class ExpressionException extends RuntimeException {
	/** The depth within the expression tree at which the exception occurred. */
	private int depth;

	/** The total number of expression nodes in the tree at the time of the exception. */
	private long nodeCount;

	/**
	 * Constructs an expression exception with a message and context information.
	 *
	 * @param message   a description of the error
	 * @param depth     the expression tree depth at the failure point
	 * @param nodeCount the total number of expression nodes in the tree
	 */
	public ExpressionException(String message, int depth, long nodeCount) {
		super(message);
		this.depth = depth;
		this.nodeCount = nodeCount;
	}
}
