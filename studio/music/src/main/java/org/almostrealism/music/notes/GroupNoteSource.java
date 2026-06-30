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

package org.almostrealism.music.notes;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.almostrealism.audio.notes.NoteAudio;
import org.almostrealism.audio.notes.NoteAudioGroup;
import org.almostrealism.audio.tone.KeyboardTuning;

import java.util.List;

/**
 * A {@link NoteAudioSource} backed by a single {@link NoteAudioGroup}.
 *
 * <p>The whole group is surfaced as <em>one</em> candidate: {@link #getNotes()}
 * returns a singleton list holding the group, so the inherited
 * {@link NoteAudioSource#getPatternNotes()} wraps it in exactly one
 * {@link SimplePatternNote}. The group therefore appears as a single entry in a
 * {@link NoteAudioChoice}'s flattened candidate list — selection lands on "the
 * group" atomically, and the per-pitch member resolution happens later inside
 * {@link NoteAudioGroup#nearest(org.almostrealism.audio.tone.KeyPosition)}.</p>
 *
 * <p>This keeps the existing index-based selection machinery (and the
 * {@link NoteAudioChoice}/{@link NoteAudioContext} contracts) unchanged: a group
 * is just another candidate the evolved selection index can land on, beside
 * ordinary {@link FileNoteSource}/{@link TreeNoteSource} samples.</p>
 *
 * @see NoteAudioGroup
 * @see NoteAudioSource
 * @see SimplePatternNote
 */
public class GroupNoteSource implements NoteAudioSource {

	/** The group this source surfaces as a single selectable candidate. */
	private final NoteAudioGroup group;

	/** Human-readable origin identifier (e.g. the saved group's key). */
	private final String origin;

	/**
	 * Creates a source over the given group.
	 *
	 * @param group  the group to surface as one candidate; must not be {@code null}
	 * @param origin a human-readable origin identifier for this source (e.g. the
	 *               saved group's key), or {@code null}
	 */
	public GroupNoteSource(NoteAudioGroup group, String origin) {
		this.group = group;
		this.origin = origin;
	}

	/** Returns the backing {@link NoteAudioGroup}. */
	public NoteAudioGroup getGroup() {
		return group;
	}

	@Override
	public String getOrigin() {
		return origin;
	}

	@Override
	public void setTuning(KeyboardTuning tuning) {
		if (group != null) {
			group.setTuning(tuning);
		}
	}

	@Override
	@JsonIgnore
	public List<NoteAudio> getNotes() {
		return List.of(group);
	}

	/**
	 * A group source references no individual file path of its own; member files
	 * are tracked as standalone library entries. Always returns {@code false}.
	 *
	 * @param canonicalPath the canonical file path to check
	 * @return {@code false}
	 */
	@Override
	public boolean checkResourceUsed(String canonicalPath) {
		return false;
	}
}
