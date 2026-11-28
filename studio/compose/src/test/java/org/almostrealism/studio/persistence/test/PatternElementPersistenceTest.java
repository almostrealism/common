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

package org.almostrealism.studio.persistence.test;

import com.google.protobuf.InvalidProtocolBufferException;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.music.filter.ParameterizedFilterEnvelope;
import org.almostrealism.music.filter.ParameterizedVolumeEnvelope;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.music.notes.PatternNoteAudioChoice;
import org.almostrealism.music.pattern.NoteAudioChoiceList;
import org.almostrealism.music.pattern.NoteDurationStrategy;
import org.almostrealism.music.pattern.PatternDirection;
import org.almostrealism.music.pattern.PatternElement;
import org.almostrealism.music.pattern.PatternLayer;
import org.almostrealism.music.pattern.PatternLayerManager;
import org.almostrealism.music.pattern.PatternNoteFactory;
import org.almostrealism.music.pattern.ScaleTraversalStrategy;
import org.almostrealism.studio.AudioSceneLoader;
import org.almostrealism.studio.persistence.PatternElementPersistence;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Round-trip tests for {@link PatternElementPersistence}: a full pattern encoded to the
 * {@link Audio.PatternData} protobuf, serialized to bytes, parsed and decoded must equal
 * the original, and so must a {@link PatternLayer} tree (choice reference, elements and
 * child layers) encoded to {@link Audio.PatternLayerData}.
 */
public class PatternElementPersistenceTest extends TestSuiteBase {

	/**
	 * Builds a melodic element the way {@code PatternElementFactory} does: a three-layer
	 * note whose layers are shaped by layer envelopes, wrapped in filter and volume
	 * envelopes for each voicing.
	 */
	private static PatternElement melodicElement(PatternNoteFactory noteFactory,
												 ParameterizedFilterEnvelope filterEnvelope,
												 ParameterizedVolumeEnvelope volumeEnvelope,
												 PackedCollection automation) {
		ParameterSet params = new ParameterSet(0.31, -0.72, 0.18);
		PatternNote choice = noteFactory.apply(params, null, true, 0.12, 0.47, 0.83);

		PatternNote main = volumeEnvelope.apply(params, ChannelInfo.Voicing.MAIN,
				filterEnvelope.apply(params, ChannelInfo.Voicing.MAIN, choice));
		PatternNote wet = volumeEnvelope.apply(params, ChannelInfo.Voicing.WET,
				filterEnvelope.apply(params, ChannelInfo.Voicing.WET, choice));

		PatternElement element = new PatternElement(main, wet, 2.25);
		element.setDurationStrategy(NoteDurationStrategy.FIXED);
		element.setNoteDurationSelection(0.375);
		element.setScaleTraversalStrategy(ScaleTraversalStrategy.SEQUENCE);
		element.setScalePosition(List.of(0.0, 0.25, 0.625));
		element.setDirection(PatternDirection.BACKWARD);
		element.setRepeatCount(4);
		element.setRepeatDuration(0.5);
		element.setAutomationParameters(automation);
		return element;
	}

	/** Builds a full pattern with several elements, notes, strategies and automation data. */
	private List<PatternElement> fullPattern() {
		PatternNoteFactory noteFactory = new PatternNoteFactory();
		ParameterizedFilterEnvelope filterEnvelope =
				ParameterizedFilterEnvelope.random(ParameterizedFilterEnvelope.Mode.STANDARD_NOTE);
		ParameterizedVolumeEnvelope volumeEnvelope =
				ParameterizedVolumeEnvelope.random(ParameterizedVolumeEnvelope.Mode.NOTE_LAYER);

		PatternElement melodic = melodicElement(noteFactory, filterEnvelope, volumeEnvelope,
				pack(0.1, 0.2, 0.3, 0.4, 0.5, 0.6).reshape(shape(2, 3)));

		PatternNote layered = new PatternNote(0.2, 0.65);
		layered.setAggregationChoice(0.4);
		PatternElement percussive = new PatternElement(layered, 1.5);
		percussive.setDurationStrategy(NoteDurationStrategy.NO_OVERLAP);
		percussive.setAutomationParameters(pack(0.9, -0.25, 1.0 / 3.0));

		PatternElement unblended = new PatternElement(
				noteFactory.apply(new ParameterSet(0.5, 0.5, 0.5), ChannelInfo.Voicing.MAIN,
						false, 0.05, 0.5, 0.95), 3.0);
		unblended.setRepeatCount(2);
		unblended.setRepeatDuration(0.25);

		return List.of(melodic, percussive, unblended);
	}

	/** Encodes to bytes, parses the bytes and decodes the result. */
	private static List<PatternElement> roundTrip(List<PatternElement> pattern)
			throws InvalidProtocolBufferException {
		byte[] bytes = PatternElementPersistence.toPatternData(pattern).toByteArray();
		return PatternElementPersistence.fromPatternData(Audio.PatternData.parseFrom(bytes));
	}

	/** Asserts that the decoded elements are distinct instances with equal content, in order. */
	private void assertContentEquals(List<PatternElement> expected, List<PatternElement> actual) {
		assertEquals(expected.size(), actual.size());
		for (int i = 0; i < expected.size(); i++) {
			assertNotSame(expected.get(i), actual.get(i));
			Assert.assertTrue("Element " + i, expected.get(i).contentEquals(actual.get(i)));
		}
	}

	/** A full pattern survives serialization and deserialization as an equal object. */
	@Test(timeout = 60000)
	public void fullPatternRoundTripsToEqualElements() throws InvalidProtocolBufferException {
		List<PatternElement> pattern = fullPattern();
		List<PatternElement> decoded = roundTrip(pattern);

		assertContentEquals(pattern, decoded);
		assertEquals(pattern.get(0).getNotes(), decoded.get(0).getNotes());
		assertEquals(pattern.get(0).getAutomationParameters().getShape(),
				decoded.get(0).getAutomationParameters().getShape());
		assertNull(decoded.get(2).getAutomationParameters());
	}

	/**
	 * Re-encoding a decoded pattern reproduces the original encoding exactly, which also
	 * covers state that content equality does not compare (such as a note's aggregation
	 * choice and the envelope selection functions behind each filter).
	 */
	@Test(timeout = 60000)
	public void decodedPatternReencodesIdentically() throws InvalidProtocolBufferException {
		List<PatternElement> pattern = fullPattern();
		Audio.PatternData original = PatternElementPersistence.toPatternData(pattern);
		Audio.PatternData reencoded = PatternElementPersistence.toPatternData(roundTrip(pattern));
		assertEquals(original, reencoded);
	}

	/** A default element, including its default strategies, round-trips unchanged. */
	@Test(timeout = 60000)
	public void defaultElementRoundTrips() throws InvalidProtocolBufferException {
		PatternElement element = new PatternElement(new PatternNote(0.3), 0.0);
		assertContentEquals(List.of(element), roundTrip(List.of(element)));
	}

	/** Unset strategies and voicings are restored as null rather than as a default. */
	@Test(timeout = 60000)
	public void nullStrategiesRoundTripAsNull() throws InvalidProtocolBufferException {
		PatternElement element = new PatternElement(new PatternNote(0.3), 0.75);
		element.setDurationStrategy(null);
		element.setScaleTraversalStrategy(null);
		element.setDirection(null);

		PatternElement decoded = roundTrip(List.of(element)).get(0);
		assertNull(decoded.getDurationStrategy());
		assertNull(decoded.getScaleTraversalStrategy());
		assertNull(decoded.getDirection());
		Assert.assertTrue(element.contentEquals(decoded));
	}

	/**
	 * A difference in automation data alone makes two elements unequal in content, while
	 * {@link Object#equals} keeps comparing elements by instance for the render caches.
	 */
	@Test(timeout = 60000)
	public void automationDifferenceIsDetected() throws InvalidProtocolBufferException {
		PatternElement element = new PatternElement(new PatternNote(0.3), 1.0);
		element.setAutomationParameters(pack(0.1, 0.2, 0.3));

		PatternElement decoded = roundTrip(List.of(element)).get(0);
		Assert.assertTrue(element.contentEquals(decoded));
		assertNotEquals(element, decoded);

		decoded.setAutomationParameters(pack(0.1, 0.2, 0.30001));
		Assert.assertFalse(element.contentEquals(decoded));
	}

	/** A filter with no protobuf representation is rejected rather than silently dropped. */
	@Test(timeout = 60000)
	public void unsupportedFilterIsRejected() {
		PatternNote note = new PatternNote(new PatternNoteAudioChoice(0.5),
				(audio, duration, automationLevel) -> audio);
		PatternElement element = new PatternElement(note, 0.0);

		try {
			PatternElementPersistence.toElementData(element);
			Assert.fail("Encoding a filter with no protobuf representation should fail");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("note audio filter"));
		}
	}

	/**
	 * Builds a three-layer chain the way {@code PatternLayerManager} chains layers: a
	 * root holding the full pattern, a child bound to a different choice, and a
	 * grandchild bound to the root's choice again.
	 */
	private PatternLayer layerTree(NoteAudioChoice kick, NoteAudioChoice lead) {
		List<PatternElement> pattern = fullPattern();

		PatternElement offbeat = new PatternElement(new PatternNote(0.6, 0.1), 0.5);
		offbeat.setRepeatCount(3);
		offbeat.setRepeatDuration(1.0);
		offbeat.setAutomationParameters(pack(0.2, 0.4, 0.8));

		PatternLayer root = new PatternLayer(lead, new ArrayList<>(pattern.subList(0, 2)));
		PatternLayer child = new PatternLayer(kick, new ArrayList<>(List.of(offbeat)));
		PatternLayer grandchild = new PatternLayer(lead, new ArrayList<>(pattern.subList(2, 3)));
		root.setChild(child);
		child.setChild(grandchild);
		return root;
	}

	/** Encodes a layer chain to bytes, parses the bytes and decodes them against the choices. */
	private static PatternLayer roundTrip(PatternLayer layer, List<NoteAudioChoice> choices)
			throws InvalidProtocolBufferException {
		byte[] bytes = PatternElementPersistence.toLayerData(layer).toByteArray();
		return PatternElementPersistence.fromLayerData(Audio.PatternLayerData.parseFrom(bytes), choices);
	}

	/**
	 * A layer tree survives serialization and deserialization as an equal structure: each
	 * layer refers to the same choice, holds content-equal elements in order, and is
	 * chained to an equal child, while the decoded layers and elements are new instances.
	 */
	@Test(timeout = 60000)
	public void layerTreeRoundTripsToEqualStructure() throws InvalidProtocolBufferException {
		NoteAudioChoice kick = new NoteAudioChoice("Kicks");
		NoteAudioChoice lead = new NoteAudioChoice("Lead");
		lead.setMelodic(true);

		PatternLayer root = layerTree(kick, lead);
		PatternLayer decoded = roundTrip(root, List.of(kick, lead));

		Assert.assertTrue(root.contentEquals(decoded));
		Assert.assertTrue(decoded.contentEquals(root));
		assertEquals(3, decoded.depth());

		PatternLayer expected = root;
		PatternLayer actual = decoded;

		while (expected != null) {
			assertNotSame(expected, actual);
			assertSame(expected.getChoice(), actual.getChoice());
			assertContentEquals(expected.getElements(), actual.getElements());
			expected = expected.getChild();
			actual = actual.getChild();
		}

		assertNull(actual);
		assertSame(lead, decoded.getChoice());
		assertSame(kick, decoded.getChild().getChoice());
		assertSame(lead, decoded.getTail().getChoice());
	}

	/** Re-encoding a decoded layer tree reproduces the original encoding exactly. */
	@Test(timeout = 60000)
	public void decodedLayerTreeReencodesIdentically() throws InvalidProtocolBufferException {
		NoteAudioChoice kick = new NoteAudioChoice("Kicks");
		NoteAudioChoice lead = new NoteAudioChoice("Lead");

		PatternLayer root = layerTree(kick, lead);
		Audio.PatternLayerData original = PatternElementPersistence.toLayerData(root);
		Audio.PatternLayerData reencoded = PatternElementPersistence.toLayerData(
				roundTrip(root, List.of(lead, kick)));

		assertEquals(original, reencoded);
		assertEquals(lead.getId(), original.getChoiceId());
		assertEquals(kick.getId(), original.getChild().getChoiceId());
		assertEquals(PatternElementPersistence.toPatternData(root.getElements()), original.getPattern());
	}

	/**
	 * The choice reference is the id the scene's choices file identifies a choice by, so
	 * a layer decoded against a choice list read back from that file resolves to the
	 * choices of that list, the way a saved pattern is restored in a later session.
	 */
	@Test(timeout = 60000)
	public void choiceReferenceResolvesAgainstReloadedChoiceList() throws IOException {
		NoteAudioChoice kick = new NoteAudioChoice("Kicks");
		NoteAudioChoice lead = new NoteAudioChoice("Lead");
		PatternLayer root = layerTree(kick, lead);

		NoteAudioChoiceList saved = new NoteAudioChoiceList();
		saved.add(kick);
		saved.add(lead);

		NoteAudioChoiceList reloaded = AudioSceneLoader.defaultMapper().readValue(
				AudioSceneLoader.defaultMapper().writeValueAsBytes(saved), NoteAudioChoiceList.class);
		assertNotSame(kick, reloaded.get(0));

		PatternLayer decoded = roundTrip(root, reloaded);
		assertSame(reloaded.get(1), decoded.getChoice());
		assertSame(reloaded.get(0), decoded.getChild().getChoice());
		assertSame(reloaded.get(1), decoded.getTail().getChoice());
		assertEquals("Lead", decoded.getChoice().getName());
		assertEquals("Kicks", decoded.getChild().getChoice().getName());

		Assert.assertFalse(root.contentEquals(decoded));
		assertEquals(PatternElementPersistence.toLayerData(root),
				PatternElementPersistence.toLayerData(decoded));
	}

	/**
	 * A layer with no choice, a null element list, an empty element list and no child are
	 * each restored as they were rather than as one another.
	 */
	@Test(timeout = 60000)
	public void absentLayerPartsRoundTripAsAbsent() throws InvalidProtocolBufferException {
		PatternLayer empty = new PatternLayer();
		PatternLayer nullElements = new PatternLayer(null, null);
		empty.setChild(nullElements);

		PatternLayer decoded = roundTrip(empty, List.of());
		assertNull(decoded.getChoice());
		Assert.assertTrue(decoded.getElements().isEmpty());
		assertNull(decoded.getChild().getChoice());
		assertNull(decoded.getChild().getElements());
		assertNull(decoded.getChild().getChild());

		Assert.assertTrue(empty.contentEquals(decoded));
		Assert.assertFalse(empty.contentEquals(decoded.getChild()));
		Assert.assertFalse(decoded.getChild().contentEquals(new PatternLayer()));
	}

	/**
	 * A chain as deep as a pattern can generate, ending in a deeply composed element,
	 * survives parsing with the parser's default recursion limit.
	 */
	@Test(timeout = 60000)
	public void deepestGeneratedChainRoundTrips() throws InvalidProtocolBufferException {
		NoteAudioChoice choice = new NoteAudioChoice("Lead");
		PatternLayer root = new PatternLayer(choice, new ArrayList<>());
		PatternLayer tail = root;

		for (int i = 1; i < PatternLayerManager.MAX_LAYERS; i++) {
			PatternLayer next = new PatternLayer(choice,
					new ArrayList<>(List.of(new PatternElement(new PatternNote(0.3), i))));
			tail.setChild(next);
			tail = next;
		}

		tail.setElements(new ArrayList<>(fullPattern()));

		PatternLayer decoded = roundTrip(root, List.of(choice));
		assertEquals(PatternLayerManager.MAX_LAYERS, decoded.depth());
		Assert.assertTrue(root.contentEquals(decoded));
	}

	/** A difference in the choice, an element or the chain makes two layer trees unequal. */
	@Test(timeout = 60000)
	public void layerDifferencesAreDetected() throws InvalidProtocolBufferException {
		NoteAudioChoice kick = new NoteAudioChoice("Kicks");
		NoteAudioChoice lead = new NoteAudioChoice("Lead");
		List<NoteAudioChoice> choices = List.of(kick, lead);
		PatternLayer root = layerTree(kick, lead);

		PatternLayer otherChoice = roundTrip(root, choices);
		otherChoice.getChild().setChoice(lead);
		Assert.assertFalse(root.contentEquals(otherChoice));

		PatternLayer otherElement = roundTrip(root, choices);
		otherElement.getTail().getElements().get(0).setRepeatCount(7);
		Assert.assertFalse(root.contentEquals(otherElement));

		PatternLayer reordered = roundTrip(root, choices);
		reordered.getElements().add(reordered.getElements().remove(0));
		Assert.assertFalse(root.contentEquals(reordered));

		PatternLayer shorter = roundTrip(root, choices);
		shorter.getLastParent().setChild(null);
		Assert.assertFalse(root.contentEquals(shorter));
		Assert.assertFalse(shorter.contentEquals(root));

		Assert.assertFalse(root.contentEquals(null));
		Assert.assertTrue(root.contentEquals(root));
	}

	/** A choice reference the choice list cannot resolve is rejected rather than dropped. */
	@Test(timeout = 60000)
	public void unresolvedChoiceReferenceIsRejected() {
		NoteAudioChoice kick = new NoteAudioChoice("Kicks");
		NoteAudioChoice lead = new NoteAudioChoice("Lead");
		Audio.PatternLayerData data = PatternElementPersistence.toLayerData(layerTree(kick, lead));

		try {
			PatternElementPersistence.fromLayerData(data, List.of(lead));
			Assert.fail("Decoding a reference to a choice that is not in the list should fail");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains(kick.getId()));
		}
	}

	/** A choice with no id cannot be referenced, so encoding a layer bound to it fails. */
	@Test(timeout = 60000)
	public void choiceWithoutIdIsRejected() {
		NoteAudioChoice choice = new NoteAudioChoice("Unidentified");
		choice.setId(null);

		try {
			PatternElementPersistence.toLayerData(new PatternLayer(choice, new ArrayList<>()));
			Assert.fail("Encoding a reference to a choice with no id should fail");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("Unidentified"));
		}
	}
}
