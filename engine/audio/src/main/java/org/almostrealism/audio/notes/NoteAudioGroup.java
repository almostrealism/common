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

package org.almostrealism.audio.notes;

import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Validity;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link NoteAudio} that delivers a target pitch from the nearest captured
 * member of a collection of pitched samples, rather than rate-shifting a single
 * sample across the whole range.
 *
 * <p>A {@code NoteAudioGroup} is the render-time face of a saved audio layer
 * group — e.g. an AudioUnit instrument sampled chromatically across a MIDI
 * range, where each member carries its own captured pitch. When asked for a
 * pitch {@code T} it picks the member whose captured root is nearest to
 * {@code T} (see {@link #nearest(KeyPosition)}) and lets that member perform the
 * <em>residual</em> rate-shift through the ordinary {@link NoteAudioProvider}
 * mechanism. When the group was captured chromatically and {@code T} is in
 * range, an exact member exists and the residual ratio is {@code 1.0} — a pure
 * passthrough with no resampling and no timbral distortion.</p>
 *
 * <h2>Nearest-member selection</h2>
 *
 * <p>Because equal temperament is monotonic in key index, nearest pitch is
 * nearest key index:</p>
 * <pre>
 * nearest(T) = argmin over pitched members i of | Mi.root.position() - T.position() |
 * </pre>
 * with a deterministic tie-break that prefers the <em>lower</em> member (so the
 * residual shift is upward, the regime where the per-note and batched resample
 * paths agree best). Targets outside the captured range resolve to the nearest
 * extreme member.</p>
 *
 * <h2>Backward compatibility</h2>
 *
 * <p>A single-member group is behaviourally identical to that bare member: its
 * {@link #nearest(KeyPosition)} is trivially that member and the residual shift
 * is exactly the member's own {@code getTone(T)/getTone(root)} ratio — same
 * field, same formula, same cache. This makes a one-member group render
 * byte-for-byte like the underlying sample.</p>
 *
 * <h2>Pitchless and unreadable members</h2>
 *
 * <p>Members with no usable captured pitch (a {@code null} root or a
 * {@link KeyPosition#none() none} position), and members whose audio cannot be
 * read (an {@linkplain NoteAudioProvider#isValid() invalid} provider, such as a
 * missing or malformed file), are excluded from the nearest-set; they never
 * participate in pitched delivery. A group with no usable pitched members is
 * {@linkplain #isValid() invalid} and is filtered out of the selectable candidate
 * set before render rather than producing audio.</p>
 *
 * @see NoteAudioProvider
 * @see NoteAudio
 * @see KeyPosition
 */
public class NoteAudioGroup implements NoteAudio, Validity {

	/**
	 * The member samples, each an ordinary {@link NoteAudioProvider} carrying its
	 * own captured root pitch. May include pitchless members, which
	 * {@link #nearest(KeyPosition)} excludes.
	 */
	private final List<NoteAudioProvider> members;

	/**
	 * Memoized {@link NoteAudioProvider#isValid()} result per member, keyed by
	 * member identity. {@link #pitchedMembers()} runs on every
	 * {@link #nearest(KeyPosition)}/{@link #isValid()}/{@link #representativeMember()}
	 * call — and for an unreadable file each {@code isValid()} reads metadata and
	 * logs a warning — so without memoization a group with a bad member re-reads it
	 * and warns once per pitched lookup throughout a render. Caching makes the check
	 * and its warning happen once per member; a member added later is evaluated on
	 * first use.
	 */
	private final Map<NoteAudioProvider, Boolean> readable = new IdentityHashMap<>();

	/** Creates an empty group; mostly useful for incremental construction. */
	public NoteAudioGroup() {
		this(List.of());
	}

	/**
	 * Creates a group over the given members.
	 *
	 * @param members the member samples, each carrying its own captured root
	 *                pitch; must not be {@code null}
	 */
	public NoteAudioGroup(List<NoteAudioProvider> members) {
		this.members = new ArrayList<>(members);
	}

	/** Returns the live list of members (including any pitchless ones). */
	public List<NoteAudioProvider> getMembers() {
		return members;
	}

	/**
	 * Returns the members eligible for nearest-pitch selection — those carrying a
	 * usable captured pitch (a non-{@code null} root whose
	 * {@link KeyPosition#position() position} is non-negative) whose audio can be
	 * read ({@link NoteAudioProvider#isValid()}).
	 *
	 * <p>A member whose file is missing, unreadable or malformed would otherwise be
	 * selected like any other and fail only when its audio is evaluated, inside a
	 * render: its provider reports a zero sample rate and no data, so the evaluation
	 * dereferences absent audio. Such a member is excluded here, exactly as an invalid
	 * single sample is filtered out of a choice's candidates, so the nearest readable
	 * member delivers the pitch instead.</p>
	 *
	 * @return the pitched, readable members, in member order
	 */
	private List<NoteAudioProvider> pitchedMembers() {
		List<NoteAudioProvider> pitched = new ArrayList<>(members.size());
		for (NoteAudioProvider m : members) {
			KeyPosition<?> root = m.getRoot();
			if (root != null && root.position() >= 0 && isReadable(m)) {
				pitched.add(m);
			}
		}
		return pitched;
	}

	/**
	 * Returns whether the member's audio can be read, memoizing
	 * {@link NoteAudioProvider#isValid()} so it is evaluated — and, for an
	 * unreadable file, warns — only once per member rather than on every
	 * pitched lookup.
	 */
	private synchronized boolean isReadable(NoteAudioProvider member) {
		return readable.computeIfAbsent(member, NoteAudioProvider::isValid);
	}

	/**
	 * Returns the member whose captured root pitch is nearest the target, or
	 * {@code null} when the group has no usable pitched members.
	 *
	 * <p>Distance is measured in key index ({@code |root.position - target.position|}),
	 * which equals nearest-in-Hz for equal temperament. Ties are broken
	 * deterministically in favour of the lower member (smaller position), so an
	 * equidistant target shifts up rather than down. When {@code target} is
	 * {@code null} (no pitch requested) the lowest pitched member is returned and
	 * will play at its own natural pitch.</p>
	 *
	 * @param target the desired pitch, or {@code null} for no specific pitch
	 * @return the nearest pitched member, or {@code null} if none is usable
	 */
	public NoteAudioProvider nearest(KeyPosition<?> target) {
		List<NoteAudioProvider> pitched = pitchedMembers();
		if (pitched.isEmpty()) return null;

		int t = (target == null) ? -1 : target.position();

		NoteAudioProvider best = null;
		int bestDistance = Integer.MAX_VALUE;
		int bestPosition = Integer.MAX_VALUE;

		for (NoteAudioProvider m : pitched) {
			int position = m.getRoot().position();
			int distance = Math.abs(position - t);

			// Strictly-closer wins; on a tie prefer the lower member (shift up).
			if (distance < bestDistance ||
					(distance == bestDistance && position < bestPosition)) {
				best = m;
				bestDistance = distance;
				bestPosition = position;
			}
		}

		return best;
	}

	@Override
	public Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel) {
		NoteAudioProvider member = nearest(target);
		if (member == null) {
			throw new IllegalStateException(
					"NoteAudioGroup has no usable pitched members");
		}

		return member.getAudio(target, channel);
	}

	@Override
	public double getDuration(KeyPosition<?> target) {
		NoteAudioProvider member = nearest(target);
		return member == null ? 0.0 : member.getDuration(target);
	}

	@Override
	public WaveData getWaveData() {
		NoteAudioProvider representative = representativeMember();
		return representative == null ? null : representative.getWaveData();
	}

	@Override
	public int getSampleRate() {
		NoteAudioProvider representative = representativeMember();
		return representative == null ? OutputLine.sampleRate : representative.getSampleRate();
	}

	@Override
	public void setTuning(KeyboardTuning tuning) {
		members.forEach(m -> m.setTuning(tuning));
	}

	/**
	 * A group is usable only when it has at least one pitched member to deliver
	 * audio from; pitchless-only and empty groups report {@code false} so the
	 * selection layer filters them out before render.
	 */
	@Override
	public boolean isValid() {
		return !pitchedMembers().isEmpty();
	}

	/**
	 * Returns a representative member for metadata queries — the first pitched
	 * member if any, otherwise the first member, or {@code null} when empty.
	 */
	private NoteAudioProvider representativeMember() {
		List<NoteAudioProvider> pitched = pitchedMembers();
		if (!pitched.isEmpty()) return pitched.get(0);
		return members.isEmpty() ? null : members.get(0);
	}
}
