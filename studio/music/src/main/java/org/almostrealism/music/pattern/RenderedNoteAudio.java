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

package org.almostrealism.music.pattern;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.data.ChannelInfo;

import java.util.function.IntFunction;

/**
 * Represents a note audio sample ready for rendering at a specific frame offset.
 *
 * <p>{@code RenderedNoteAudio} is the output of {@link ScaleTraversalStrategy#getNoteDestinations}
 * and serves as the bridge between pattern elements and actual audio rendering. Each instance
 * contains:</p>
 * <ul>
 *   <li><strong>offsetArg</strong>: A note-owned {@link PackedCollection} for passing the start
 *       frame offset to the producer factory. The note owns this native allocation and releases
 *       it in {@link #destroy()}; a render caller writes the start frame into it before each
 *       {@link #getProducer(int)} call but does not free it</li>
 *   <li><strong>producerFactory</strong>: A function that creates a {@link Producer} for a given
 *       frame count, using the offset stored in {@code offsetArg}</li>
 *   <li><strong>offset</strong>: The absolute frame position where this note should be rendered
 *       in the destination buffer</li>
 * </ul>
 *
 * <h2>Rendering Process</h2>
 *
 * <p>In {@link PatternFeatures#render}, each {@code RenderedNoteAudio} is processed by setting
 * the start frame in {@link #getOffsetArg()}, then calling {@link #getProducer(int)} with the
 * desired frame count. The resulting audio is cached in a {@link NoteAudioCache} keyed by the
 * composite of the note offset and this note's {@link #getCacheIdentity() cacheIdentity} for
 * reuse across buffer ticks; the identity is what keeps coincident notes (chords, layered
 * voices, stereo channels) at the same offset from sharing a cache entry. The overlap region
 * is then summed to the destination buffer.</p>
 *
 * <h2>Signature Independence</h2>
 *
 * <p>The producer factory creates producers whose computation signature is independent of
 * the start frame value (because the offset is a runtime {@link PackedCollection} argument,
 * not a structural parameter). This enables compiled kernel reuse across different frame
 * positions via the instruction set cache.</p>
 *
 * @see PatternElement#getNoteDestinations
 * @see ScaleTraversalStrategy#getNoteDestinations
 * @see PatternFeatures#render
 *
 * @author Michael Murray
 */
public class RenderedNoteAudio implements Destroyable {
	/**
	 * Stable identity of a rendered note, used together with its frame offset to
	 * key a {@link NoteAudioCache}.
	 *
	 * <p>The element is compared by instance (it is the same object across buffer
	 * ticks) and the voicing details by value (voicing, target pitch, position), so
	 * coincident chord tones sharing a frame offset stay distinct while the same
	 * note stays equal across ticks. The stereo channel is a separate component
	 * because {@link ElementVoicingDetails#equals} ignores it, while the rendered
	 * audio reads channel-specific sample data and a single cache serves both
	 * channels of a {@link PatternLayerManager}.</p>
	 *
	 * <p>The voicing details are snapshotted on the way in and on the way out, so the
	 * identity never shares a details instance with code outside it: the canonical
	 * constructor copies its argument into a private instance, and {@link #details()}
	 * returns a fresh copy rather than that instance. Because the stored copy is
	 * unreachable, the key's hash is stable for the lifetime of a
	 * {@link NoteAudioCache} entry and a mutation of the caller's details (before or
	 * after construction) cannot strand the cached buffer under a changed hash.</p>
	 *
	 * @param element       the pattern element the note was rendered from
	 * @param details       the voicing details of the note (snapshotted on construction)
	 * @param stereoChannel the stereo channel the note was rendered for
	 */
	public record Identity(PatternElement element, ElementVoicingDetails details,
						   ChannelInfo.StereoChannel stereoChannel) {
		/**
		 * Canonical constructor that snapshots the voicing details (see the
		 * record-level note) so the identity cannot alias a mutable details instance
		 * supplied by the caller.
		 */
		public Identity {
			details = new ElementVoicingDetails(details);
		}

		/**
		 * Returns a snapshot of this identity's voicing details rather than the
		 * stored instance, so a caller cannot mutate the key through the accessor.
		 * The returned value is equal to the stored details, so cache equality and
		 * hashing (which read the backing field directly) are unaffected.
		 *
		 * @return a fresh copy of the voicing details
		 */
		@Override
		public ElementVoicingDetails details() {
			return new ElementVoicingDetails(details);
		}

		/**
		 * Creates the identity of the note rendered from the given element with the
		 * given voicing details, on the stereo channel those details select. The
		 * details are snapshotted by the canonical constructor so the identity is a
		 * stable key regardless of later mutation of the caller's details.
		 *
		 * @param element the pattern element
		 * @param details the voicing details to snapshot
		 * @return the note identity
		 */
		public static Identity of(PatternElement element, ElementVoicingDetails details) {
			return new Identity(element, details, details.getStereoChannel());
		}
	}

	/** The absolute frame offset in the arrangement. */
	private int offset;

	/** The estimated frame count; 0 means no estimate available. */
	private int expectedFrameCount;

	/** The PackedCollection used to pass the start frame offset to producers. */
	private PackedCollection offsetArg;

	/** Factory that creates audio producers for a given sample rate. */
	private IntFunction<Producer<PackedCollection>> producerFactory;

	/**
	 * The batched-kernel input record for this note, or {@code null} when the
	 * note is not the melodic-SSS shape (in which case the batched dispatch
	 * falls back to per-note rendering).
	 */
	private BatchedNoteInputs batchedInputs;

	/**
	 * Stable identity distinguishing this note from other notes that begin at the
	 * same {@link #offset}. Used by {@link NoteAudioCache} so coincident notes
	 * (chords, layered voices) do not share a cache entry. Must be equal across
	 * buffer ticks for the same note and distinct between coincident notes; may be
	 * {@code null}, in which case the offset alone identifies the cache entry.
	 */
	private Identity cacheIdentity;

	/**
	 * Creates a RenderedNoteAudio with an expected frame count for pre-filtering.
	 *
	 * <p>The {@code expectedFrameCount} enables overlap checks before the expensive
	 * {@code evaluate()} call. When non-zero, rendering can skip notes whose
	 * {@code [offset, offset + expectedFrameCount)} range does not overlap the
	 * target buffer.</p>
	 *
	 * @param offset absolute frame offset in the arrangement
	 * @param expectedFrameCount estimated number of frames this note will produce
	 */
	public RenderedNoteAudio(int offset, int expectedFrameCount) {
		this.offset = offset;
		this.expectedFrameCount = expectedFrameCount;
	}

	/** Returns the absolute frame offset in the arrangement. */
	public int getOffset() {
		return offset;
	}

	/** Sets the absolute frame offset. */
	public void setOffset(int offset) {
		this.offset = offset;
	}

	/**
	 * Returns the estimated number of frames this note will produce.
	 *
	 * <p>This estimate is computed from the note's duration before the
	 * producer is evaluated. A value of 0 means no estimate is available
	 * and pre-filtering should be skipped for this note.</p>
	 */
	public int getExpectedFrameCount() {
		return expectedFrameCount;
	}

	/** Sets the estimated frame count. */
	public void setExpectedFrameCount(int expectedFrameCount) {
		this.expectedFrameCount = expectedFrameCount;
	}

	/**
	 * Returns the note-owned {@link PackedCollection} used to pass the
	 * start frame offset to producers. A render caller sets the value
	 * via {@code getOffsetArg().setMem(0, startFrame)} before calling
	 * {@link #getProducer(int)}, but the note owns the allocation and releases
	 * it in {@link #destroy()}; the caller must not free it.
	 *
	 * <p>Because the same {@link PackedCollection} instance is reused across
	 * calls, the {@link org.almostrealism.collect.computations.CollectionProviderProducer}
	 * signature remains stable (based on memory address, not data value),
	 * enabling compiled kernel reuse via the instruction set cache.</p>
	 */
	public PackedCollection getOffsetArg() {
		return offsetArg;
	}

	/**
	 * Sets the note-owned PackedCollection used to pass the start frame offset to
	 * producers. The note takes ownership of its native memory and releases it in
	 * {@link #destroy()}.
	 *
	 * <p>Because the note owns the allocation, replacing an existing offset argument
	 * with a different instance releases the previous one: it was the note's to free,
	 * and overwriting the only reference to it would otherwise strand it until GC.
	 * Setting the same instance again, or clearing it with {@code null}, leaves the
	 * current allocation untouched.</p>
	 */
	public void setOffsetArg(PackedCollection offsetArg) {
		if (offsetArg == this.offsetArg || offsetArg == null) {
			return;
		}

		if (this.offsetArg != null) {
			this.offsetArg.destroy();
		}
		this.offsetArg = offsetArg;
	}

	/**
	 * Sets the factory for creating {@link Producer}s that evaluate this note's audio.
	 *
	 * <p>The factory accepts a frame count and returns a Producer that generates
	 * exactly that many output frames. The start frame offset is communicated
	 * via the {@link #getOffsetArg()} PackedCollection, which the caller sets
	 * before invoking the factory. This design keeps the computation signature
	 * independent of the actual start frame value.</p>
	 *
	 * <p>A factory must read {@link #getOffsetArg()} when it is invoked rather than
	 * capturing the instance at construction, because {@link #setOffsetArg} may
	 * replace the argument and release the previous allocation.</p>
	 *
	 * @param factory function mapping frameCount to a Producer
	 */
	public void setProducerFactory(IntFunction<Producer<PackedCollection>> factory) {
		this.producerFactory = factory;
	}

	/**
	 * Creates a {@link Producer} that evaluates the specified number of frames.
	 *
	 * <p>The caller must set the start frame offset in {@link #getOffsetArg()}
	 * before calling this method.</p>
	 *
	 * @param frameCount number of frames to produce
	 * @return a Producer for the requested frame count
	 * @throws IllegalStateException if no producer factory is set
	 */
	public Producer<PackedCollection> getProducer(int frameCount) {
		if (producerFactory == null) {
			throw new IllegalStateException(
					"No producer factory set on RenderedNoteAudio; " +
					"this indicates a missing setup in ScaleTraversalStrategy");
		}
		return producerFactory.apply(frameCount);
	}

	/**
	 * Returns the batched-kernel input record for this note, or {@code null}
	 * when the note is not the melodic-SSS shape.
	 *
	 * @return the batched inputs, or {@code null}
	 */
	public BatchedNoteInputs getBatchedInputs() {
		return batchedInputs;
	}

	/**
	 * Sets the batched-kernel input record for this note.
	 *
	 * @param batchedInputs the batched inputs, or {@code null} if unsupported
	 */
	public void setBatchedInputs(BatchedNoteInputs batchedInputs) {
		this.batchedInputs = batchedInputs;
	}

	/**
	 * Returns the stable per-note identity used to key this note in a
	 * {@link NoteAudioCache}, or {@code null} if none was set.
	 *
	 * @return the cache identity, or {@code null}
	 */
	public Identity getCacheIdentity() {
		return cacheIdentity;
	}

	/**
	 * Sets the stable per-note identity used to distinguish coincident notes in a
	 * {@link NoteAudioCache}.
	 *
	 * @param cacheIdentity the cache identity, or {@code null}
	 */
	public void setCacheIdentity(Identity cacheIdentity) {
		this.cacheIdentity = cacheIdentity;
	}

	/**
	 * Releases the native memory this note owns.
	 *
	 * <p>The only native allocation a {@code RenderedNoteAudio} owns is its
	 * {@link #getOffsetArg() offset argument}, a single-element {@link PackedCollection}
	 * created per note. It is destroyed and the reference cleared, so a repeated call
	 * is a no-op. The {@link #getBatchedInputs() batched inputs} are deliberately not
	 * touched: a memoized melodic note's batched sources are stable raw sample
	 * references the note does not own, so destroying them here would free memory
	 * still owned by the sample library.</p>
	 */
	@Override
	public void destroy() {
		if (offsetArg != null) {
			offsetArg.destroy();
			offsetArg = null;
		}
	}
}
