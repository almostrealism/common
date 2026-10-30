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

package org.almostrealism.hardware.cuda;

import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.streams.LatchSemaphore;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.hardware.ctx.StreamSemaphore;

/**
 * The completion handle for one submission to a {@link CudaStreamRunner}: the CUDA analog of
 * {@link org.almostrealism.hardware.metal.MetalSemaphore}.
 *
 * <p>A submission may be launched immediately or held back behind a foreign dependency (see
 * {@link CudaStreamRunner#submit}), so the semaphore exists before the work reaches the stream.
 * The runner counts it down once its completion thread has observed the work finish on the GPU
 * (or the submission failed, which is recorded with {@link #fail(Throwable)} first) and has run
 * the submission's completion callback, so a caller returning from {@link #waitFor()} may rely on
 * every effect of that callback. That settlement state is a {@link LatchSemaphore}, shared by
 * every {@link #withRequester(OperationMetadata) re-attributed view} of the completion.</p>
 *
 * <p>Knowing its {@link #getStream() runner} is what lets a dependent submission to the same
 * runner skip any ordering work: the runner's stream already executes in submission order. Its
 * {@link #getOrdinal() sequence number} is what lets two completions of the same runner merge
 * into one (see {@link #merge(Semaphore)}).</p>
 */
public class CudaSemaphore extends StreamSemaphore<CudaStreamRunner> {
	/** The settlement state: counted down once the submission has settled, holding its failure. */
	private final LatchSemaphore settlement;

	/**
	 * Creates the completion handle for a new submission.
	 *
	 * @param requester metadata of the operation the submission belongs to, or {@code null}
	 * @param runner    the runner the work is submitted to
	 * @param sequence  the position of the submission among all submissions to the runner
	 */
	CudaSemaphore(OperationMetadata requester, CudaStreamRunner runner, long sequence) {
		this(requester, runner, sequence, new LatchSemaphore(1));
	}

	/**
	 * Creates a handle with the given settlement state, which a re-attributed view shares with
	 * the original.
	 *
	 * @param requester  metadata of the operation this completion is attributed to, or {@code null}
	 * @param runner     the runner the work was submitted to
	 * @param sequence   the position of the submission among all submissions to the runner
	 * @param settlement the settlement state
	 */
	private CudaSemaphore(OperationMetadata requester, CudaStreamRunner runner, long sequence,
						  LatchSemaphore settlement) {
		super(requester, runner, sequence);
		this.settlement = settlement;
	}

	/** Returns true once the submission has settled, successfully or not. */
	public boolean isSettled() { return settlement.isSettled(); }

	/**
	 * Records the failure the submission settles with; only the first is retained.
	 *
	 * @param failure the failure, or {@code null} if the submission completed normally
	 */
	void fail(Throwable failure) { settlement.fail(failure); }

	/** Settles the submission, releasing its waiters and running its settlement callbacks. */
	void countDown() { settlement.countDown(); }

	/**
	 * Blocks until the submission has completed on the GPU and its completion callback has run.
	 *
	 * <p>A submission that has already settled returns (or reports its failure) immediately from
	 * any thread, including the runner's completion thread: the completion thread settles
	 * submissions in order, so a callback may rely on an earlier submission's result. Only a wait
	 * for a submission that has not settled yet is rejected there, as it would wait for the
	 * completion thread itself.</p>
	 *
	 * @throws IllegalStateException if the submission has not settled and this is called from the
	 *                               runner's completion thread, which would otherwise wait for itself
	 */
	@Override
	public void waitFor() {
		if (!isSettled()) getStream().requireOffStreamThread();
		settlement.waitFor();
	}

	/**
	 * Registers the callback with the settlement state, which the runner's completion thread
	 * counts down, so no thread is held while the submission runs.
	 *
	 * @param r the callback to invoke once the submission has settled
	 */
	@Override
	public void onSettled(Runnable r) {
		settlement.onSettled(r);
	}

	/**
	 * Merges with another completion of the same {@link CudaStreamRunner} into whichever of the
	 * two was submitted later. A runner settles its submissions in submission order (see
	 * {@link CudaStreamRunner#submit}), so the later one cannot settle before the earlier one has,
	 * and a dependent submission to the same runner is ordered after both by the stream alone.
	 * Completions of another runner, or of any other provider, are not merged.
	 *
	 * @param other another completion
	 * @return the later completion when both belong to this runner, otherwise {@code null}
	 */
	@Override
	public Semaphore merge(Semaphore other) {
		return later(other);
	}

	@Override
	public Semaphore withRequester(OperationMetadata requester) {
		return new CudaSemaphore(requester, getStream(), getOrdinal(), settlement);
	}
}
