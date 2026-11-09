/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.benchmark;

import org.almostrealism.hardware.InstructionCacheCollisionException;

/**
 * What happened when one {@link WorkloadInstance} was compiled and evaluated: how it ended, and
 * the kernel binding events of its first evaluation and of the evaluations after it.
 */
public class InstanceResult {
	/** How an instance ended. */
	public enum Outcome {
		/** Every evaluation produced the reference output. */
		VERIFIED,
		/** An evaluation completed but produced output other than the reference. */
		MISMATCH,
		/** Binding a reused kernel threw an {@link InstructionCacheCollisionException}. */
		COLLISION,
		/** Compilation or evaluation failed for any other reason. */
		FAILURE
	}

	/** The instance. */
	private final WorkloadInstance instance;
	/** How it ended. */
	private final Outcome outcome;
	/** The kind of collision, for {@link Outcome#COLLISION}. */
	private final InstructionCacheCollisionException.Kind collisionKind;
	/** A description of the mismatch, collision or failure, or null when verified. */
	private final String detail;
	/** The type of the exception that failed the instance, for {@link Outcome#FAILURE}. */
	private final String failureType;
	/** The events of the first evaluation, including every compilation. */
	private final KernelBindingRecorder.Events first;
	/** The events of the evaluations after the first. */
	private final KernelBindingRecorder.Events repeat;
	/** The wall-clock time to build, optimize, compile and first evaluate the computation. */
	private final long firstEvaluationNanos;

	/**
	 * Creates a result.
	 *
	 * @param instance             the instance
	 * @param outcome              how it ended
	 * @param collisionKind        the kind of collision, or null
	 * @param detail               a description of what went wrong, or null
	 * @param failureType          the simple name of the exception type that failed the instance,
	 *                             or null unless the outcome is a failure
	 * @param first                the events of the first evaluation
	 * @param repeat               the events of the later evaluations
	 * @param firstEvaluationNanos the wall-clock time up to the end of the first evaluation
	 */
	public InstanceResult(WorkloadInstance instance, Outcome outcome,
						  InstructionCacheCollisionException.Kind collisionKind, String detail,
						  String failureType, KernelBindingRecorder.Events first, KernelBindingRecorder.Events repeat,
						  long firstEvaluationNanos) {
		this.instance = instance;
		this.outcome = outcome;
		this.collisionKind = collisionKind;
		this.detail = detail;
		this.failureType = failureType;
		this.first = first;
		this.repeat = repeat;
		this.firstEvaluationNanos = firstEvaluationNanos;
	}

	/** Returns the instance. */
	public WorkloadInstance getInstance() { return instance; }

	/** Returns how the instance ended. */
	public Outcome getOutcome() { return outcome; }

	/** Returns the kind of collision, or null when the outcome is not a collision. */
	public InstructionCacheCollisionException.Kind getCollisionKind() { return collisionKind; }

	/** Returns a description of what went wrong, or null when verified. */
	public String getDetail() { return detail; }

	/**
	 * Returns a name for what went wrong that depends only on how the instance ended: the outcome,
	 * and for a failure the type of the exception. Unlike {@link #getDetail()}, it never includes a
	 * message, which can name generated functions or be absent when the JVM omits it.
	 *
	 * @return the problem, or null when the instance was verified or collided
	 */
	public String getProblem() {
		if (outcome == Outcome.MISMATCH) return outcome.name();
		if (outcome == Outcome.FAILURE) return outcome.name() + ": " + failureType;
		return null;
	}

	/** Returns the events of the first evaluation. */
	public KernelBindingRecorder.Events getFirst() { return first; }

	/** Returns the events of the evaluations after the first. */
	public KernelBindingRecorder.Events getRepeat() { return repeat; }

	/** Returns the wall-clock time up to the end of the first evaluation, in nanoseconds. */
	public long getFirstEvaluationNanos() { return firstEvaluationNanos; }
}
