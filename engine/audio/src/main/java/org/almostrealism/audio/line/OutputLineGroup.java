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

package org.almostrealism.audio.line;

import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An {@link OutputLine} implementation that wraps multiple member output lines
 * and keeps them synchronized by computing read position as the minimum across
 * all members. This prevents any device from running ahead of the slowest
 * device in the group, avoiding audible drift in multi-device playback.
 *
 * <p>{@link #write(PackedCollection)} forwards audio data to all member lines.
 * {@link #getReadPosition()} returns the minimum read position, ensuring the
 * {@link BufferedOutputScheduler} paces itself to the slowest consumer.</p>
 *
 * <p>Member management is thread-safe via {@link CopyOnWriteArrayList}.</p>
 *
 * @see BufferedOutputScheduler
 * @see SourceDataOutputLine
 */
public class OutputLineGroup implements OutputLine {

	/** The member output lines in this group. */
	private final List<OutputLine> members;

	/** Creates an empty output line group. */
	public OutputLineGroup() {
		this.members = new CopyOnWriteArrayList<>();
	}

	/**
	 * Creates an output line group with the specified initial members.
	 *
	 * @param members the initial member lines
	 */
	public OutputLineGroup(List<OutputLine> members) {
		this.members = new CopyOnWriteArrayList<>(members);
	}

	/**
	 * Adds a member line to this group.
	 *
	 * @param member the output line to add
	 */
	public void addMember(OutputLine member) {
		members.add(member);
	}

	/**
	 * Removes a member line from this group.
	 *
	 * @param member the output line to remove
	 * @return true if the member was found and removed
	 */
	public boolean removeMember(OutputLine member) {
		return members.remove(member);
	}

	/**
	 * Returns the current number of members in the group.
	 *
	 * @return the member count
	 */
	public int size() {
		return members.size();
	}

	/**
	 * Returns the member at the specified index.
	 *
	 * @param index the member index
	 * @return the member output line
	 */
	public OutputLine getMember(int index) {
		return members.get(index);
	}

	/**
	 * Writes audio data to all member lines.
	 *
	 * @param sample the audio data to write
	 */
	@Override
	public void write(PackedCollection sample) {
		for (OutputLine member : members) {
			member.write(sample);
		}
	}

	/**
	 * Returns the minimum read position across all member lines. This ensures
	 * the scheduler paces itself to the slowest device in the group, preventing
	 * any device from falling behind.
	 *
	 * @return the minimum read position in frames, or 0 if the group is empty
	 */
	@Override
	public int getReadPosition() {
		int min = Integer.MAX_VALUE;

		for (OutputLine member : members) {
			int pos = member.getReadPosition();
			if (pos < min) {
				min = pos;
			}
		}

		return min == Integer.MAX_VALUE ? 0 : min;
	}

	/**
	 * Returns the minimum buffer size across all member lines. The scheduler
	 * will use this as its buffer size, ensuring writes fit within the smallest
	 * member buffer.
	 *
	 * @return the minimum buffer size in frames, or the default if the group is empty
	 */
	@Override
	public int getBufferSize() {
		int min = Integer.MAX_VALUE;

		for (OutputLine member : members) {
			int size = member.getBufferSize();
			if (size < min) {
				min = size;
			}
		}

		return min == Integer.MAX_VALUE ? BufferDefaults.defaultBufferSize : min;
	}

	/** Starts all member lines. */
	@Override
	public void start() {
		for (OutputLine member : members) {
			member.start();
		}
	}

	/** Stops all member lines. */
	@Override
	public void stop() {
		for (OutputLine member : members) {
			member.stop();
		}
	}

	/** Returns true if any member line is active. */
	@Override
	public boolean isActive() {
		for (OutputLine member : members) {
			if (member.isActive()) return true;
		}
		return false;
	}

	/** Resets all member lines. */
	@Override
	public void reset() {
		for (OutputLine member : members) {
			member.reset();
		}
	}

	/** Destroys all member lines. */
	@Override
	public void destroy() {
		for (OutputLine member : members) {
			member.destroy();
		}
		members.clear();
	}
}
