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

package org.almostrealism.studio;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.CellFeatures;

import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.graph.CollectionCachedStateCell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.graph.SummationCell;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Multi-channel audio mixer that routes a fixed number of input channels into a single
 * summed output. Each channel is backed by a {@link SummationCell}, allowing independent
 * audio streams to be combined into a unified output {@link CellList}.
 *
 * <h2>Output Groups</h2>
 * <p>When multi-device playback is configured, output groups partition the channels
 * into subsets, each summed into its own {@link SummationCell}. Each channel belongs
 * to exactly one output group. The expensive per-channel rendering happens once;
 * group summation is a trivially cheap addition of already-computed values.</p>
 *
 * <p>When no output groups are configured, all channels feed the default sum
 * and the mixer behaves identically to its single-output mode.</p>
 *
 * @see SampleMixer
 * @see SummationCell
 */
public class Mixer implements CellFeatures {
	/** Per-channel summation cells that accumulate audio data from each input channel. */
	private final SummationCell[] channels;

	/** The output cell list that sums all channel contributions into a single stream. */
	private CellList cells;

	/**
	 * Named output groups, each representing a subset of channels summed
	 * into a dedicated {@link SummationCell} for a specific output device.
	 */
	private final Map<String, OutputGroup> outputGroups;

	/** Creates a {@code Mixer} with the default channel count (24). */
	public Mixer() { this(24); }

	/**
	 * Creates a {@code Mixer} with the specified number of channels.
	 *
	 * @param channelCount the number of independent mix channels
	 */
	public Mixer(int channelCount) {
		this.channels = new SummationCell[channelCount];
		this.outputGroups = new LinkedHashMap<>();
		this.cells = cells(channelCount, i -> {
			channels[i] = new SummationCell();
			return channels[i];
		}).sum();
	}

	/**
	 * Returns the number of input channels in this mixer.
	 *
	 * @return the channel count
	 */
	public int getChannelCount() { return channels.length; }

	/**
	 * Returns the {@link CollectionCachedStateCell} for the specified channel index.
	 *
	 * @param i the zero-based channel index
	 * @return the channel's summation cell
	 */
	public CollectionCachedStateCell getChannel(int i) {
		return channels[i];
	}

	/**
	 * Returns the summed output {@link CellList}. When no output groups are
	 * configured, this contains the single default sum of all channels.
	 *
	 * @return the cell list containing the mixed output
	 */
	public CellList getCells() { return cells; }

	/**
	 * Returns the final summed output cell.
	 *
	 * @return the output {@link SummationCell}
	 */
	public SummationCell getOutput() { return (SummationCell) cells.get(0); }

	/**
	 * Delivers the mixed audio output to the specified output line via buffered scheduling.
	 *
	 * @param out the audio output line
	 * @return a {@link BufferedOutputScheduler} that drives the output
	 */
	public BufferedOutputScheduler buffer(OutputLine out) {
		return getCells().buffer(out);
	}

	/**
	 * Adds an output group that sums the specified channels into a dedicated
	 * {@link SummationCell}. Each channel must belong to exactly one output group.
	 *
	 * <p>After all output groups are added, call {@link #applyOutputGroups()} to
	 * rebuild the cell wiring.</p>
	 *
	 * @param name           a unique name for this group (typically the device name)
	 * @param channelIndices the channel indices assigned to this group
	 * @return the created output group
	 * @throws IllegalArgumentException if the name is already in use
	 */
	public OutputGroup addOutputGroup(String name, int... channelIndices) {
		if (outputGroups.containsKey(name)) {
			throw new IllegalArgumentException("Output group already exists: " + name);
		}

		SummationCell groupOutput = new SummationCell();
		OutputGroup group = new OutputGroup(name, channelIndices, groupOutput);
		outputGroups.put(name, group);
		return group;
	}

	/**
	 * Rebuilds the cell pipeline to reflect the configured output groups. Each
	 * channel's {@link SummationCell} is wired to push to its assigned group's
	 * {@link SummationCell}. The default all-channels sum is replaced by the
	 * per-group sums.
	 *
	 * <p>This must be called after all output groups have been added via
	 * {@link #addOutputGroup(String, int...)} and before the pipeline is
	 * delivered to any {@link BufferedOutputScheduler}.</p>
	 */
	public void applyOutputGroups() {
		if (outputGroups.isEmpty()) return;

		// Wire each channel to its group's SummationCell
		for (OutputGroup group : outputGroups.values()) {
			for (int idx : group.channelIndices()) {
				channels[idx].setReceptor(group.groupOutput());
			}
		}

		// Rebuild the CellList: channel cells + group output cells
		CellList channelCells = cells(channels.length, i -> channels[i]);

		SummationCell[] groupOutputs = outputGroups.values().stream()
				.map(OutputGroup::groupOutput)
				.toArray(SummationCell[]::new);

		CellList groupCells = cells(groupOutputs.length, i -> groupOutputs[i]);
		this.cells = groupCells.addRequirements(channelCells);
	}

	/**
	 * Returns a {@link CellList} for a single output group, containing the
	 * group's {@link SummationCell} with its assigned channel cells as
	 * requirements. This CellList can be independently scheduled to deliver
	 * audio to a specific device.
	 *
	 * <p>Since each channel belongs to exactly one group, the cells in the
	 * returned CellList do not overlap with those of any other group,
	 * ensuring single-pass rendering when multiple groups are scheduled
	 * concurrently.</p>
	 *
	 * @param name the group name
	 * @return a CellList for this group's output
	 * @throws IllegalArgumentException if the group does not exist
	 */
	public CellList getGroupCells(String name) {
		OutputGroup group = outputGroups.get(name);
		if (group == null) {
			throw new IllegalArgumentException("No output group: " + name);
		}

		int[] indices = group.channelIndices();
		CellList channelCells = cells(indices.length, i -> channels[indices[i]]);
		CellList groupCell = cells(group.groupOutput());
		return groupCell.addRequirements(channelCells);
	}

	/**
	 * Returns the named output group, or null if it does not exist.
	 *
	 * @param name the group name
	 * @return the output group, or null
	 */
	public OutputGroup getOutputGroup(String name) {
		return outputGroups.get(name);
	}

	/**
	 * Returns all configured output groups.
	 *
	 * @return unmodifiable view of the output groups (insertion-ordered)
	 */
	public Map<String, OutputGroup> getOutputGroups() {
		return Map.copyOf(outputGroups);
	}

	/**
	 * Returns true if output groups have been configured.
	 *
	 * @return true if at least one output group exists
	 */
	public boolean hasOutputGroups() {
		return !outputGroups.isEmpty();
	}

	/**
	 * Describes a subset of mixer channels that are summed into a dedicated
	 * output {@link SummationCell} for delivery to a specific audio device.
	 *
	 * @param name           the group name (typically the device name)
	 * @param channelIndices the channel indices assigned to this group
	 * @param groupOutput    the summation cell that accumulates this group's channels
	 */
	public record OutputGroup(String name, int[] channelIndices, SummationCell groupOutput) {
	}
}
