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

package org.almostrealism.music.notes;
import org.almostrealism.audio.notes.NoteAudioProvider;

import org.almostrealism.audio.data.DelegateWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.WaveDataProvider;

import java.util.ArrayList;
import java.util.Collection;

/**
 * A leaf {@link NoteAudioNode} that wraps an audio file provider reference.
 *
 * <p>Stores the name and identifier of the underlying wave data provider, along with
 * optional delegate-offset information for split providers. Factory methods convert
 * {@link WaveDataProvider} and {@link org.almostrealism.audio.notes.NoteAudioProvider}
 * instances into nodes suitable for use in the note audio tree.</p>
 *
 * @see NoteAudioNode
 */
public class AudioProviderNode implements NoteAudioNode {
	/** Human-readable name of this node, typically derived from the provider key. */
	private String name;

	/** Unique identifier for this node, derived from the provider. */
	private String identifier;

	/** Whether this node delegates to a sub-range of a parent provider. */
	private boolean delegate;

	/** Sample offset into the delegate provider's data. */
	private int delegateOffset;

	/** Number of samples in this node's sub-range. */
	private int length;

	/** Creates an uninitialized {@code AudioProviderNode}. */
	public AudioProviderNode() { }

	/**
	 * Creates a non-delegate {@code AudioProviderNode} with the given name and identifier.
	 *
	 * @param name       the human-readable name
	 * @param identifier the unique identifier
	 */
	protected AudioProviderNode(String name, String identifier) {
		this.name = name;
		this.identifier = identifier;
		this.delegate = false;
	}

	/**
	 * Creates a delegate {@code AudioProviderNode} with the given name, identifier, offset, and length.
	 *
	 * @param name           the human-readable name
	 * @param identifier     the unique identifier
	 * @param delegateOffset the sample offset into the parent provider
	 * @param length         the number of samples in this node
	 */
	protected AudioProviderNode(String name, String identifier, int delegateOffset, int length) {
		this.name = name;
		this.identifier = identifier;
		this.delegate = true;
		this.delegateOffset = delegateOffset;
		this.length = length;
	}

	public void setName(String name) { this.name = name; }

	@Override
	public String getName() { return name; }

	@Override
	public String getIdentifier() { return identifier; }

	@Override
	public void setIdentifier(String identifier) { this.identifier = identifier; }

	public boolean isDelegate() { return delegate; }
	public void setDelegate(boolean delegate) { this.delegate = delegate; }

	public int getDelegateOffset() { return delegateOffset; }
	public void setDelegateOffset(int delegateOffset) { this.delegateOffset = delegateOffset; }

	public int getLength() { return length; }
	public void setLength(int length) { this.length = length; }

	@Override
	public Collection<NoteAudioNode> getChildren() {
		return new ArrayList<>();
	}

	/**
	 * Creates an {@code AudioProviderNode} from a {@link WaveDataProvider}, if supported.
	 *
	 * @param provider the wave data provider
	 * @return the node, or null if the provider type is not supported
	 */
	public static AudioProviderNode create(WaveDataProvider provider) {
		if (provider instanceof FileWaveDataProvider) {
			return new AudioProviderNode(provider.getKey(), provider.getIdentifier());
		} else if (provider instanceof DelegateWaveDataProvider &&
				((DelegateWaveDataProvider) provider).getDelegate() instanceof FileWaveDataProvider p) {
			return new AudioProviderNode(p.getKey(), p.getIdentifier(),
					((DelegateWaveDataProvider) provider).getDelegateOffset(),
					provider.getCount());
		} else {
			return null;
		}
	}

	/**
	 * Creates an {@code AudioProviderNode} from a {@link NoteAudioProvider}.
	 *
	 * @param note the note audio provider
	 * @return the node, or null if the underlying provider type is not supported
	 */
	public static AudioProviderNode create(NoteAudioProvider note) {
		return create(note.getProvider());
	}
}
