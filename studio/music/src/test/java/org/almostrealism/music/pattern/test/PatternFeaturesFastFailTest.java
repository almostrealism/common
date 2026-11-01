/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.music.pattern.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperatorPoolExhaustedException;
import org.almostrealism.music.arrange.AudioSceneContext;
import org.almostrealism.music.notes.NoteAudioContext;
import org.almostrealism.music.pattern.PatternElement;
import org.almostrealism.music.pattern.PatternFeatures;
import org.almostrealism.music.pattern.RenderedNoteAudio;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that {@link PatternFeatures#renderPerNote} aborts a render quickly
 * when notes fail to evaluate, instead of emitting thousands of warnings and
 * timing out. Two conditions are exercised:
 *
 * <ol>
 *   <li>Exhaustion-class failure (an {@link OperatorPoolExhaustedException}
 *       thrown when the runtime native-lib template class pool is
 *       exhausted) aborts on the first occurrence.</li>
 *   <li>Generic per-note failures accumulate and abort after the
 *       {@link PatternFeatures#MAX_CONSECUTIVE_NOTE_FAILURES} threshold.</li>
 * </ol>
 */
public class PatternFeaturesFastFailTest extends TestSuiteBase implements PatternFeatures {

	/**
	 * Builds a {@link PatternElement} whose {@code getNoteDestinations} returns
	 * a single {@link RenderedNoteAudio} whose producer factory throws the
	 * given exception. This bypasses the normal scale-traversal pipeline so
	 * the test does not require an {@link org.almostrealism.studio.AudioScene}.
	 *
	 * @param offset    the note start frame
	 * @param toThrow   the exception thrown when the producer is requested
	 * @return a single-note pattern element wired to fail
	 */
	private static PatternElement failingElement(int offset, RuntimeException toThrow) {
		return observedElement(offset, () -> {
			throw toThrow;
		});
	}

	/**
	 * Creates a minimal audio scene context for testing.
	 *
	 * @return the minimal context with default values
	 */
	private static AudioSceneContext minimalContext() {
		AudioSceneContext ctx = new AudioSceneContext();
		ctx.setDestination(new PackedCollection(1024));
		ctx.setFrames(1024);
		ctx.setMeasures(1);
		return ctx;
	}

	/**
	 * The first {@link OperatorPoolExhaustedException} failure aborts the render
	 * immediately. Subsequent failing notes are not evaluated.
	 */
	@Test(timeout = 30_000)
	public void exhaustionFailureAbortsImmediately() {
		AudioSceneContext ctx = minimalContext();

		List<PatternElement> elements = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			elements.add(failingElement(i * 64,
					new OperatorPoolExhaustedException(
							new RuntimeException("synthetic-cause"))));
		}

		try {
			renderPerNote(ctx, null, elements, false, 0.0, 0, 1024, null);
			Assert.fail("renderPerNote should have thrown IllegalStateException");
		} catch (IllegalStateException e) {
			Assert.assertTrue(
					"message must reference exhaustion: " + e.getMessage(),
					e.getMessage().contains("template class pool exhausted"));
			Assert.assertTrue("first failure should report consecutiveFailures=1: " + e.getMessage(),
					e.getMessage().contains("consecutiveFailures=1"));
		}
	}

	/**
	 * Generic per-note failures (no exhaustion message) trigger abort only
	 * after {@link PatternFeatures#MAX_CONSECUTIVE_NOTE_FAILURES} consecutive
	 * failures. Earlier failures emit warnings without throwing.
	 */
	@Test(timeout = 30_000)
	public void consecutiveFailuresAbortAfterThreshold() {
		AudioSceneContext ctx = minimalContext();

		List<PatternElement> elements = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			elements.add(failingElement(i * 64,
					new RuntimeException("synthetic non-exhaustion failure " + i)));
		}

		try {
			renderPerNote(ctx, null, elements, false, 0.0, 0, 1024, null);
			Assert.fail("renderPerNote should have thrown IllegalStateException");
		} catch (IllegalStateException e) {
			Assert.assertTrue(
					"message must reference consecutive-failure abort: " + e.getMessage(),
					e.getMessage().contains("consecutive note-evaluation failures"));
			Assert.assertTrue(
					"abort should happen at exactly " + MAX_CONSECUTIVE_NOTE_FAILURES
							+ " consecutive failures: " + e.getMessage(),
					e.getMessage().contains("after " + MAX_CONSECUTIVE_NOTE_FAILURES));
		}
	}

	/**
	 * Builds a {@link PatternElement} whose single note runs {@code onRequest} when its
	 * producer is requested and then fails the evaluation, as an evaluation abandoned by
	 * an interrupt does.
	 *
	 * @param offset    the note start frame
	 * @param onRequest run each time the note's producer is requested
	 * @return a single-note pattern element
	 */
	private static PatternElement observedElement(int offset, Runnable onRequest) {
		return new PatternElement() {
			@Override
			public List<RenderedNoteAudio> getNoteDestinations(boolean melodic, double off,
															   AudioSceneContext context,
															   NoteAudioContext audioContext) {
				RenderedNoteAudio note = new RenderedNoteAudio(offset, 0);
				note.setOffsetArg(new PackedCollection(1));
				note.setProducerFactory(frameCount -> {
					onRequest.run();
					throw new RuntimeException("evaluation abandoned at frame " + offset);
				});
				return List.of(note);
			}
		};
	}

	/**
	 * A render whose thread is interrupted evaluates no note, fails with a
	 * {@link CancellationException} rather than the consecutive-failure abort, and leaves the
	 * interrupt status set for whoever requested the cancellation.
	 */
	@Test(timeout = 30_000)
	public void interruptedRenderEvaluatesNoNote() {
		AudioSceneContext ctx = minimalContext();
		AtomicInteger requested = new AtomicInteger();

		List<PatternElement> elements = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			elements.add(observedElement(i * 8, requested::incrementAndGet));
		}

		Thread.currentThread().interrupt();

		try {
			renderPerNote(ctx, null, elements, false, 0.0, 0, 1024, null);
			Assert.fail("renderPerNote should have been cancelled");
		} catch (CancellationException e) {
			Assert.assertTrue("the cancelling interrupt must remain set",
					Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
		}

		Assert.assertEquals("no note may be evaluated on an interrupted thread", 0, requested.get());
	}

	/**
	 * A render interrupted part way through (a timed-out caller, a stopped render-ahead
	 * thread) stops at the next note instead of evaluating the rest of the pattern, which
	 * would otherwise keep dispatching work until the consecutive-failure bound, or never
	 * when abandoned evaluations do not fail.
	 */
	@Test(timeout = 30_000)
	public void renderInterruptedMidwayStopsAtNextNote() {
		AudioSceneContext ctx = minimalContext();
		AtomicInteger requested = new AtomicInteger();

		List<PatternElement> elements = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			elements.add(observedElement(i * 8, () -> {
				if (requested.incrementAndGet() == 2) Thread.currentThread().interrupt();
			}));
		}

		try {
			renderPerNote(ctx, null, elements, false, 0.0, 0, 1024, null);
			Assert.fail("renderPerNote should have been cancelled");
		} catch (CancellationException e) {
			Assert.assertTrue("message should name the frame the render stopped at: " + e.getMessage(),
					e.getMessage().contains("frame 16"));
		} finally {
			Thread.interrupted();
		}

		Assert.assertEquals("only the notes before the interrupt may be evaluated", 2, requested.get());
	}
}
