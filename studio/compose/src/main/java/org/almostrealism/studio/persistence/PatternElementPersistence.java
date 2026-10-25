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

package org.almostrealism.studio.persistence;

import com.google.protobuf.ProtocolMessageEnum;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.notes.NoteAudioFilter;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.MultipleParameterFunction;
import org.almostrealism.music.data.ParameterFunction;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.music.filter.ParameterizedEnvelope;
import org.almostrealism.music.filter.ParameterizedEnvelopeAdapter;
import org.almostrealism.music.filter.ParameterizedEnvelopeFilter;
import org.almostrealism.music.filter.ParameterizedEnvelopeLayers;
import org.almostrealism.music.filter.ParameterizedFilterEnvelope;
import org.almostrealism.music.filter.ParameterizedLayerEnvelope;
import org.almostrealism.music.filter.ParameterizedVolumeEnvelope;
import org.almostrealism.music.notes.PatternNote;
import org.almostrealism.music.notes.PatternNoteAudio;
import org.almostrealism.music.notes.PatternNoteAudioChoice;
import org.almostrealism.music.notes.PatternNoteLayer;
import org.almostrealism.music.pattern.NoteDurationStrategy;
import org.almostrealism.music.pattern.PatternDirection;
import org.almostrealism.music.pattern.PatternElement;
import org.almostrealism.music.pattern.ScaleTraversalStrategy;
import org.almostrealism.persist.assets.CollectionEncoder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Codec between the pattern model of the music module ({@link PatternElement} and the
 * types it aggregates) and the {@link Audio.PatternData} / {@link Audio.PatternElementData}
 * protobuf messages.
 *
 * <p>The generated protobuf types live in this module while the pattern model lives in
 * the music module, which cannot depend on this one, so the mapping cannot be a method
 * of either type and is kept here alongside the other {@link Audio} codecs.</p>
 *
 * <h2>Fidelity</h2>
 *
 * <p>Every property of a {@link PatternElement} round-trips exactly: per-voicing notes,
 * position, {@link NoteDurationStrategy} and note duration selection,
 * {@link ScaleTraversalStrategy} and scale positions, {@link PatternDirection}, repeat
 * count and duration, and the automation parameters (stored at full FP64 precision).
 * The strategies and direction are closed enums; each protobuf enum's zero value stands
 * for a null Java field and every other value carries the Java constant's name.</p>
 *
 * <p>A {@link PatternNote} is a composition of {@link PatternNoteAudio} sources and
 * {@link NoteAudioFilter}s, both open interfaces. The format represents the composition
 * the pattern model builds: {@link PatternNoteAudioChoice} selections,
 * {@link PatternNoteLayer}s, nested {@link PatternNote}s in either layer or delegate mode,
 * and filters created by a {@link ParameterizedEnvelope} (volume, filter and layer
 * envelopes). An envelope filter is stored as the envelope's definition together with
 * the {@link ParameterSet} and voicing it was created with, and is restored by asking
 * an equal envelope to create it again, so it is reproduced exactly rather than
 * approximated. Any other implementation (for example a filter wrapping an arbitrary
 * {@code Factor}, or note audio bound directly to a sample source) has no faithful
 * representation, so encoding it fails with an {@link IllegalArgumentException} instead
 * of silently dropping it. The same applies to a subclass of {@link PatternElement},
 * whose overridden behaviour cannot be persisted.</p>
 *
 * @see PatternElement
 * @see MidiPatternPersistence
 */
public final class PatternElementPersistence {

	/** Not instantiable; all members are static. */
	private PatternElementPersistence() { }

	/**
	 * Encodes an ordered list of pattern elements.
	 *
	 * @param elements the elements of the pattern
	 * @return the encoded pattern
	 * @throws IllegalArgumentException if an element contains a type the format cannot represent
	 */
	public static Audio.PatternData toPatternData(List<PatternElement> elements) {
		Audio.PatternData.Builder data = Audio.PatternData.newBuilder();
		elements.forEach(e -> data.addElements(toElementData(e)));
		return data.build();
	}

	/**
	 * Decodes an ordered list of pattern elements.
	 *
	 * @param data the encoded pattern
	 * @return the decoded elements, in order
	 */
	public static List<PatternElement> fromPatternData(Audio.PatternData data) {
		return data.getElementsList().stream()
				.map(PatternElementPersistence::fromElementData)
				.collect(Collectors.toCollection(ArrayList::new));
	}

	/**
	 * Encodes a single pattern element.
	 *
	 * @param element the element to encode
	 * @return the encoded element
	 * @throws IllegalArgumentException if the element contains a type the format cannot represent
	 */
	public static Audio.PatternElementData toElementData(PatternElement element) {
		if (element.getClass() != PatternElement.class) {
			throw unsupported("pattern element", element);
		}

		Audio.PatternElementData.Builder data = Audio.PatternElementData.newBuilder()
				.setPosition(element.getPosition())
				.setDurationStrategy(toProtoEnum(Audio.PatternElementData.NoteDurationStrategy.class,
						element.getDurationStrategy()))
				.setNoteDurationSelection(element.getNoteDurationSelection())
				.setScaleTraversalStrategy(toProtoEnum(Audio.PatternElementData.ScaleTraversalStrategy.class,
						element.getScaleTraversalStrategy()))
				.addAllScalePositions(Objects.requireNonNull(element.getScalePositions(), "scalePositions"))
				.setDirection(toProtoEnum(Audio.PatternElementData.PatternDirection.class,
						element.getDirection()))
				.setRepeatCount(element.getRepeatCount())
				.setRepeatDuration(element.getRepeatDuration());

		Objects.requireNonNull(element.getNotes(), "notes").entrySet().stream()
				.sorted(Map.Entry.comparingByKey(Comparator.nullsFirst(Comparator.naturalOrder())))
				.forEach(entry -> {
					Audio.VoicedPatternNote.Builder note = Audio.VoicedPatternNote.newBuilder()
							.setVoicing(toProtoEnum(Audio.PatternVoicing.class, entry.getKey()));
					encodeIfPresent(entry.getValue(), PatternElementPersistence::toNoteData, note::setNote);
					data.addNotes(note);
				});

		encodeIfPresent(element.getAutomationParameters(), CollectionEncoder::encode,
				data::setAutomationParameters);
		return data.build();
	}

	/**
	 * Decodes a single pattern element.
	 *
	 * @param data the encoded element
	 * @return the decoded element
	 */
	public static PatternElement fromElementData(Audio.PatternElementData data) {
		Map<ChannelInfo.Voicing, PatternNote> notes = new HashMap<>();
		data.getNotesList().forEach(n -> notes.put(
				fromProtoEnum(ChannelInfo.Voicing.class, n.getVoicing()),
				n.hasNote() ? fromNoteData(n.getNote()) : null));

		PatternElement element = new PatternElement(notes, data.getPosition());
		element.setDurationStrategy(fromProtoEnum(NoteDurationStrategy.class, data.getDurationStrategy()));
		element.setNoteDurationSelection(data.getNoteDurationSelection());
		element.setScaleTraversalStrategy(
				fromProtoEnum(ScaleTraversalStrategy.class, data.getScaleTraversalStrategy()));
		element.setScalePosition(new ArrayList<>(data.getScalePositionsList()));
		element.setDirection(fromProtoEnum(PatternDirection.class, data.getDirection()));
		element.setRepeatCount(data.getRepeatCount());
		element.setRepeatDuration(data.getRepeatDuration());

		if (data.hasAutomationParameters()) {
			element.setAutomationParameters(CollectionEncoder.decode(data.getAutomationParameters(), true));
		}

		return element;
	}

	/**
	 * Encodes a {@link PatternNote}, including every layer, delegate and filter it is composed of.
	 *
	 * @param note the note to encode
	 * @return the encoded note
	 * @throws IllegalArgumentException if the note contains a type the format cannot represent
	 */
	public static Audio.PatternNoteData toNoteData(PatternNote note) {
		if (note.getLayers() != null && note.getDelegate() != null) {
			throw new IllegalArgumentException("A PatternNote cannot have both layers and a delegate");
		}

		Audio.PatternNoteData.Builder data = Audio.PatternNoteData.newBuilder()
				.setAggregationChoice(note.getAggregationChoice());

		if (note.getLayers() != null) {
			Audio.PatternNoteLayers.Builder layers = Audio.PatternNoteLayers.newBuilder();
			note.getLayers().forEach(l -> layers.addLayers(toNoteAudioData(l)));
			data.setLayers(layers);
		}

		encodeIfPresent(note.getDelegate(), PatternElementPersistence::toNoteAudioData, data::setDelegate);
		encodeIfPresent(note.getFilter(), PatternElementPersistence::toFilterData, data::setFilter);
		return data.build();
	}

	/**
	 * Decodes a {@link PatternNote}.
	 *
	 * @param data the encoded note
	 * @return the decoded note
	 */
	public static PatternNote fromNoteData(Audio.PatternNoteData data) {
		PatternNote note;

		if (data.hasLayers()) {
			note = new PatternNote(data.getLayers().getLayersList().stream()
					.map(PatternElementPersistence::fromNoteAudioData)
					.collect(Collectors.toCollection(ArrayList::new)));
		} else {
			note = new PatternNote(
					decodeIfPresent(data.hasDelegate(), data.getDelegate(),
							PatternElementPersistence::fromNoteAudioData),
					decodeIfPresent(data.hasFilter(), data.getFilter(),
							PatternElementPersistence::fromFilterData));
		}

		note.setAggregationChoice(data.getAggregationChoice());
		return note;
	}

	/** Encodes one of the {@link PatternNoteAudio} implementations the format represents. */
	private static Audio.PatternNoteAudioData toNoteAudioData(PatternNoteAudio audio) {
		Audio.PatternNoteAudioData.Builder data = Audio.PatternNoteAudioData.newBuilder();

		if (audio instanceof PatternNoteAudioChoice choice) {
			data.setSelection(choice.getNoteAudioSelection());
		} else if (audio instanceof PatternNote note) {
			data.setNote(toNoteData(note));
		} else if (audio instanceof PatternNoteLayer layer) {
			Audio.FilteredNoteAudioData.Builder filtered = Audio.FilteredNoteAudioData.newBuilder();
			encodeIfPresent(layer.getDelegate(), PatternElementPersistence::toNoteAudioData,
					filtered::setDelegate);
			encodeIfPresent(layer.getFilter(), PatternElementPersistence::toFilterData,
					filtered::setFilter);
			data.setLayer(filtered);
		} else {
			throw unsupported("note audio", audio);
		}

		return data.build();
	}

	/** Decodes a {@link PatternNoteAudio}. */
	private static PatternNoteAudio fromNoteAudioData(Audio.PatternNoteAudioData data) {
		return switch (data.getKindCase()) {
			case SELECTION -> new PatternNoteAudioChoice(data.getSelection());
			case NOTE -> fromNoteData(data.getNote());
			case LAYER -> PatternNoteLayer.create(
					decodeIfPresent(data.getLayer().hasDelegate(), data.getLayer().getDelegate(),
							PatternElementPersistence::fromNoteAudioData),
					decodeIfPresent(data.getLayer().hasFilter(), data.getLayer().getFilter(),
							PatternElementPersistence::fromFilterData));
			case KIND_NOT_SET -> throw new IllegalArgumentException("Note audio has no content");
		};
	}

	/** Encodes a filter created by one of the {@link ParameterizedEnvelope}s the format represents. */
	private static Audio.NoteAudioFilterData toFilterData(NoteAudioFilter filter) {
		if (!(filter instanceof ParameterizedEnvelopeFilter envelopeFilter)) {
			throw unsupported("note audio filter", filter);
		}

		Audio.NoteAudioFilterData.Builder data = Audio.NoteAudioFilterData.newBuilder()
				.setVoicing(toProtoEnum(Audio.PatternVoicing.class, envelopeFilter.getVoicing()));
		encodeIfPresent(envelopeFilter.getParameters(), PatternElementPersistence::toParameterSetData,
				data::setParameters);

		ParameterizedEnvelope envelope = envelopeFilter.getEnvelope();

		if (envelope instanceof ParameterizedVolumeEnvelope volume) {
			data.setVolumeEnvelope(toAdsrData(volume));
		} else if (envelope instanceof ParameterizedFilterEnvelope filterEnvelope) {
			data.setFilterEnvelope(toAdsrData(filterEnvelope));
		} else if (envelope instanceof ParameterizedLayerEnvelope layer) {
			Audio.EnvelopeLayersData.Builder layers = Audio.EnvelopeLayersData.newBuilder();
			encodeIfPresent(layer.getAttackSelection(), PatternElementPersistence::toFunctionData,
					layers::setAttackSelection);
			encodeIfPresent(layer.getSustainSelection(), PatternElementPersistence::toFunctionData,
					layers::setSustainSelection);
			encodeIfPresent(layer.getReleaseSelection(), PatternElementPersistence::toFunctionData,
					layers::setReleaseSelection);
			encodeIfPresent(layer.getVolume0(), PatternElementPersistence::toFunctionsData, layers::setVolume0);
			encodeIfPresent(layer.getVolume1(), PatternElementPersistence::toFunctionsData, layers::setVolume1);
			encodeIfPresent(layer.getVolume2(), PatternElementPersistence::toFunctionsData, layers::setVolume2);
			encodeIfPresent(layer.getVolume3(), PatternElementPersistence::toFunctionsData, layers::setVolume3);
			data.setLayerEnvelope(Audio.LayerEnvelopeData.newBuilder()
					.setLayers(layers).setLayer(layer.getLayer()));
		} else {
			throw unsupported("envelope", envelope);
		}

		return data.build();
	}

	/** Decodes an envelope filter by recreating its envelope and asking it for the filter. */
	private static ParameterizedEnvelopeFilter fromFilterData(Audio.NoteAudioFilterData data) {
		ParameterizedEnvelope envelope = switch (data.getEnvelopeCase()) {
			case VOLUME_ENVELOPE -> {
				Audio.AdsrEnvelopeData adsr = data.getVolumeEnvelope();
				yield new ParameterizedVolumeEnvelope(
						fromProtoEnum(ParameterizedVolumeEnvelope.Mode.class, adsr.getMode()),
						function(adsr.hasAttackSelection(), adsr.getAttackSelection()),
						function(adsr.hasDecaySelection(), adsr.getDecaySelection()),
						function(adsr.hasSustainSelection(), adsr.getSustainSelection()),
						function(adsr.hasReleaseSelection(), adsr.getReleaseSelection()));
			}
			case FILTER_ENVELOPE -> {
				Audio.AdsrEnvelopeData adsr = data.getFilterEnvelope();
				yield new ParameterizedFilterEnvelope(
						fromProtoEnum(ParameterizedFilterEnvelope.Mode.class, adsr.getMode()),
						function(adsr.hasAttackSelection(), adsr.getAttackSelection()),
						function(adsr.hasDecaySelection(), adsr.getDecaySelection()),
						function(adsr.hasSustainSelection(), adsr.getSustainSelection()),
						function(adsr.hasReleaseSelection(), adsr.getReleaseSelection()));
			}
			case LAYER_ENVELOPE -> {
				Audio.EnvelopeLayersData layers = data.getLayerEnvelope().getLayers();
				yield new ParameterizedEnvelopeLayers(
						function(layers.hasAttackSelection(), layers.getAttackSelection()),
						function(layers.hasSustainSelection(), layers.getSustainSelection()),
						function(layers.hasReleaseSelection(), layers.getReleaseSelection()),
						functions(layers.hasVolume0(), layers.getVolume0()),
						functions(layers.hasVolume1(), layers.getVolume1()),
						functions(layers.hasVolume2(), layers.getVolume2()),
						functions(layers.hasVolume3(), layers.getVolume3()))
						.getEnvelope(data.getLayerEnvelope().getLayer());
			}
			case ENVELOPE_NOT_SET -> throw new IllegalArgumentException("Note audio filter has no envelope");
		};

		ParameterSet params = decodeIfPresent(data.hasParameters(), data.getParameters(),
				p -> new ParameterSet(p.getX(), p.getY(), p.getZ()));
		return envelope.createFilter(params, fromProtoEnum(ChannelInfo.Voicing.class, data.getVoicing()));
	}

	/** Encodes the mode and ADSR selection functions of a volume or filter envelope. */
	private static Audio.AdsrEnvelopeData toAdsrData(ParameterizedEnvelopeAdapter envelope) {
		Audio.AdsrEnvelopeData.Builder data = Audio.AdsrEnvelopeData.newBuilder()
				.setMode(toProtoEnum(Audio.AdsrEnvelopeData.Mode.class, envelope.getMode()));
		encodeIfPresent(envelope.getAttackSelection(), PatternElementPersistence::toFunctionData,
				data::setAttackSelection);
		encodeIfPresent(envelope.getDecaySelection(), PatternElementPersistence::toFunctionData,
				data::setDecaySelection);
		encodeIfPresent(envelope.getSustainSelection(), PatternElementPersistence::toFunctionData,
				data::setSustainSelection);
		encodeIfPresent(envelope.getReleaseSelection(), PatternElementPersistence::toFunctionData,
				data::setReleaseSelection);
		return data.build();
	}

	/** Encodes a {@link ParameterSet}. */
	private static Audio.ParameterSetData toParameterSetData(ParameterSet params) {
		return Audio.ParameterSetData.newBuilder()
				.setX(params.getX()).setY(params.getY()).setZ(params.getZ())
				.build();
	}

	/** Encodes a {@link ParameterFunction}. */
	private static Audio.ParameterFunctionData toFunctionData(ParameterFunction function) {
		return Audio.ParameterFunctionData.newBuilder()
				.setX(function.getX()).setY(function.getY()).setZ(function.getZ()).setC(function.getC())
				.build();
	}

	/** Decodes a {@link ParameterFunction}, or returns null when it is absent. */
	private static ParameterFunction function(boolean present, Audio.ParameterFunctionData data) {
		return decodeIfPresent(present, data,
				d -> new ParameterFunction(d.getX(), d.getY(), d.getZ(), d.getC()));
	}

	/** Encodes a {@link MultipleParameterFunction}. */
	private static Audio.MultipleParameterFunctionData toFunctionsData(MultipleParameterFunction functions) {
		Audio.MultipleParameterFunctionData.Builder data = Audio.MultipleParameterFunctionData.newBuilder();
		Objects.requireNonNull(functions.getFunctions(), "functions")
				.forEach(f -> data.addFunctions(toFunctionData(f)));
		return data.build();
	}

	/** Decodes a {@link MultipleParameterFunction}, or returns null when it is absent. */
	private static MultipleParameterFunction functions(boolean present, Audio.MultipleParameterFunctionData data) {
		return decodeIfPresent(present, data, d -> {
			MultipleParameterFunction functions = new MultipleParameterFunction();
			functions.setFunctions(d.getFunctionsList().stream()
					.map(f -> function(true, f))
					.collect(Collectors.toCollection(ArrayList::new)));
			return functions;
		});
	}

	/** Encodes {@code value} and passes the result to {@code setter}, unless {@code value} is null. */
	private static <M, D> void encodeIfPresent(M value, Function<M, D> encode, Consumer<D> setter) {
		if (value != null) setter.accept(encode.apply(value));
	}

	/** Decodes {@code data} when {@code present}, and otherwise returns null. */
	private static <D, M> M decodeIfPresent(boolean present, D data, Function<D, M> decode) {
		return present ? decode.apply(data) : null;
	}

	/**
	 * Maps a Java enum constant to the protobuf enum value of the same name, or a null
	 * constant to the protobuf enum's zero value.
	 */
	private static <P extends Enum<P> & ProtocolMessageEnum> P toProtoEnum(Class<P> type, Enum<?> value) {
		return value == null ? type.getEnumConstants()[0] : Enum.valueOf(type, value.name());
	}

	/**
	 * Maps a protobuf enum value to the Java enum constant of the same name, or the
	 * protobuf enum's zero value to null.
	 */
	private static <E extends Enum<E>, P extends Enum<P> & ProtocolMessageEnum> E fromProtoEnum(
			Class<E> type, P value) {
		if ("UNRECOGNIZED".equals(value.name())) {
			throw new IllegalArgumentException("Unrecognized value for " + type.getSimpleName());
		}

		return value.getNumber() == 0 ? null : Enum.valueOf(type, value.name());
	}

	/** Creates the exception reported for a model type the format cannot represent. */
	private static IllegalArgumentException unsupported(String kind, Object value) {
		return new IllegalArgumentException("Cannot persist " + kind + " of type "
				+ value.getClass().getName() + "; it has no protobuf representation");
	}
}
