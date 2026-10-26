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

package org.almostrealism.music.pattern;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.audio.notes.NoteAudioFilter;
import org.almostrealism.collect.PackedCollection;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A text grammar for the pattern model: a serializer that writes an ordered list of
 * {@link PatternElement}s (and the types they aggregate) as human-readable text, and a
 * parser that reads the same text back into the model.
 *
 * <p>The format is the text counterpart of the protobuf codec in the compose module
 * ({@code PatternElementPersistence}). It describes the same closed set of types and
 * carries the same state exactly, so a pattern converted through either format comes back
 * equal. A client application or a connected agent can therefore write an exact pattern by
 * hand, hand it to the framework to render, and read back any pattern the framework holds.
 * The two codecs necessarily live apart — the protobuf one beside its generated messages in
 * the compose module, this one beside the model in the music module — and each walks the
 * same closed set of types; the cross-format equivalence test is what fails the build if
 * either gains a type or field the other lacks.</p>
 *
 * <h2>Grammar</h2>
 *
 * <p>The format is brace-delimited and whitespace-insensitive; {@code #} begins a comment
 * that runs to the end of the line. A pattern is a {@code pattern} block of {@code element}
 * blocks; each keyword corresponds to a field (or, for {@code duration}, {@code scale} and
 * {@code repeat}, a fixed pair of fields) of the model. The keyword {@code none} denotes a
 * null value (a null strategy, direction, voicing, note, selection function, parameter set
 * or automation), and is distinct from the enumeration constant {@code NONE} of
 * {@link NoteDurationStrategy}. Enumeration values are written as their Java constant names,
 * which the protobuf enums share, so the mapping between the two formats stays mechanical.</p>
 *
 * <pre>
 * pattern {
 *   element {
 *     position 2.25
 *     duration FIXED 0.375
 *     scale SEQUENCE [0.0, 0.25, 0.625]
 *     direction BACKWARD
 *     repeat 4 every 0.5
 *     automation [2, 3] [0.1, 0.2, 0.3, 0.4, 0.5, 0.6]
 *     note MAIN {
 *       aggregation 0.0
 *       choices [0.2, 0.65]
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>The full keyword-to-field mapping and further examples are documented in the music
 * module README.</p>
 *
 * <h2>Fidelity</h2>
 *
 * <p>Numbers are written with {@link Double#toString(double)} and read with
 * {@link Double#parseDouble(String)}, which round-trip every finite double exactly, so
 * automation and envelope coefficients are never reduced in precision. Omitted element
 * fields take {@link PatternElement}'s constructor defaults, so a hand-written element is
 * playable without boilerplate; the serializer nonetheless writes every element field
 * explicitly, so a serialized pattern never depends on those defaults. A type the format
 * cannot represent (any other {@link PatternNoteAudio}, {@link NoteAudioFilter} or
 * {@link ParameterizedEnvelope} implementation, or a {@link PatternElement} subclass) makes
 * the serializer throw {@link IllegalArgumentException} rather than drop state, matching the
 * protobuf codec. Envelope filters are rebuilt the way the protobuf decoder rebuilds them:
 * the envelope is reconstructed from its definition and asked to create the filter again.</p>
 *
 * @see PatternElement#toText()
 * @see PatternElement#fromText(String)
 */
public final class PatternText {

	/** The keyword that denotes a null value, as opposed to the {@code NONE} enum constant. */
	private static final String NONE = "none";

	/** The two-space unit used to indent each nested level of the serialized form. */
	private static final String INDENT = "  ";

	/** Not instantiable; all members are static. */
	private PatternText() { }

	/**
	 * Serializes an ordered list of pattern elements to text.
	 *
	 * @param pattern the elements of the pattern, in order
	 * @return the serialized text, beginning with a {@code pattern} block
	 * @throws IllegalArgumentException if an element contains a type the format cannot represent
	 */
	public static String format(List<PatternElement> pattern) {
		StringBuilder out = new StringBuilder();
		out.append("pattern {\n");
		pattern.forEach(element -> appendElement(out, element, 1));
		out.append("}\n");
		return out.toString();
	}

	/**
	 * Serializes a single pattern element to text.
	 *
	 * @param element the element to serialize
	 * @return the serialized text, an {@code element} block
	 * @throws IllegalArgumentException if the element contains a type the format cannot represent
	 */
	public static String format(PatternElement element) {
		StringBuilder out = new StringBuilder();
		appendElement(out, element, 0);
		return out.toString();
	}

	/**
	 * Parses an ordered list of pattern elements from text.
	 *
	 * @param text the serialized text, a {@code pattern} block
	 * @return the parsed elements, in order
	 * @throws ParseException if the text is not a well-formed pattern
	 */
	public static List<PatternElement> parse(String text) {
		Parser parser = new Parser(Tokenizer.tokenize(text));
		List<PatternElement> pattern = parser.parsePattern();
		parser.expectEnd();
		return pattern;
	}

	/**
	 * Parses a single pattern element from text.
	 *
	 * @param text the serialized text, an {@code element} block
	 * @return the parsed element
	 * @throws ParseException if the text is not a well-formed element
	 */
	public static PatternElement parseElement(String text) {
		Parser parser = new Parser(Tokenizer.tokenize(text));
		PatternElement element = parser.parseElement();
		parser.expectEnd();
		return element;
	}

	/** Appends the serialized form of an element at the given indent level. */
	private static void appendElement(StringBuilder out, PatternElement element, int indent) {
		if (element.getClass() != PatternElement.class) {
			throw unsupported("pattern element", element);
		}

		line(out, indent, "element {");
		line(out, indent + 1, "position " + num(element.getPosition()));
		line(out, indent + 1, "duration " + nameOrNone(element.getDurationStrategy())
				+ " " + num(element.getNoteDurationSelection()));
		line(out, indent + 1, "scale " + nameOrNone(element.getScaleTraversalStrategy())
				+ " " + doubleList(element.getScalePositions()));
		line(out, indent + 1, "direction " + nameOrNone(element.getDirection()));
		line(out, indent + 1, "repeat " + element.getRepeatCount()
				+ " every " + num(element.getRepeatDuration()));

		PackedCollection automation = element.getAutomationParameters();
		if (automation != null) {
			line(out, indent + 1, "automation " + shapeDims(automation.getShape())
					+ " " + doubleArray(automation.doubleStream().toArray()));
		}

		sortedNotes(element.getNotes()).forEach(entry ->
				appendNoteEntry(out, entry.getKey(), entry.getValue(), indent + 1));

		line(out, indent, "}");
	}

	/** Returns the note entries ordered by voicing (null first, then MAIN, then WET). */
	private static List<Map.Entry<ChannelInfo.Voicing, PatternNote>> sortedNotes(
			Map<ChannelInfo.Voicing, PatternNote> notes) {
		List<Map.Entry<ChannelInfo.Voicing, PatternNote>> entries = new ArrayList<>(notes.entrySet());
		entries.sort(Map.Entry.comparingByKey(Comparator.nullsFirst(Comparator.naturalOrder())));
		return entries;
	}

	/** Appends a note entry for one voicing, writing the null keyword when the note is absent. */
	private static void appendNoteEntry(StringBuilder out, ChannelInfo.Voicing voicing,
										PatternNote note, int indent) {
		if (note == null) {
			line(out, indent, "note " + nameOrNone(voicing) + " " + NONE);
			return;
		}

		line(out, indent, "note " + nameOrNone(voicing) + " {");
		appendNoteBody(out, note, indent + 1);
		line(out, indent, "}");
	}

	/** Appends the body of a {@link PatternNote}: its aggregation choice and its mode. */
	private static void appendNoteBody(StringBuilder out, PatternNote note, int indent) {
		line(out, indent, "aggregation " + num(note.getAggregationChoice()));

		List<PatternNoteAudio> layers = note.getLayers();
		if (layers != null) {
			if (layers.stream().allMatch(l -> l instanceof PatternNoteAudioChoice)) {
				line(out, indent, "choices " + doubleArray(layers.stream()
						.mapToDouble(l -> ((PatternNoteAudioChoice) l).getNoteAudioSelection())
						.toArray()));
			} else {
				line(out, indent, "layers [");
				layers.forEach(l -> appendAudio(out, l, indent + 1));
				line(out, indent, "]");
			}
		} else {
			appendDelegate(out, note.getDelegate(), indent);
			appendFilter(out, note.getFilter(), indent);
		}
	}

	/** Appends a {@code delegate} wrapper holding one audio source, or {@code none}. */
	private static void appendDelegate(StringBuilder out, PatternNoteAudio delegate, int indent) {
		if (delegate == null) {
			line(out, indent, "delegate " + NONE);
			return;
		}

		line(out, indent, "delegate {");
		appendAudio(out, delegate, indent + 1);
		line(out, indent, "}");
	}

	/** Appends one {@link PatternNoteAudio} source as a tagged block. */
	private static void appendAudio(StringBuilder out, PatternNoteAudio audio, int indent) {
		if (audio instanceof PatternNoteAudioChoice choice) {
			line(out, indent, "choice " + num(choice.getNoteAudioSelection()));
		} else if (audio instanceof PatternNote note) {
			line(out, indent, "note {");
			appendNoteBody(out, note, indent + 1);
			line(out, indent, "}");
		} else if (audio instanceof PatternNoteLayer layer) {
			line(out, indent, "layer {");
			appendDelegate(out, layer.getDelegate(), indent + 1);
			appendFilter(out, layer.getFilter(), indent + 1);
			line(out, indent, "}");
		} else {
			throw unsupported("note audio", audio);
		}
	}

	/** Appends a {@code filter} wrapper holding one envelope filter, or {@code none}. */
	private static void appendFilter(StringBuilder out, NoteAudioFilter filter, int indent) {
		if (filter == null) {
			line(out, indent, "filter " + NONE);
			return;
		}

		if (!(filter instanceof ParameterizedEnvelopeFilter envelopeFilter)) {
			throw unsupported("note audio filter", filter);
		}

		line(out, indent, "filter {");
		appendEnvelope(out, envelopeFilter.getEnvelope(), indent + 1);
		line(out, indent + 1, "params " + paramSet(envelopeFilter.getParameters()));
		line(out, indent + 1, "voicing " + nameOrNone(envelopeFilter.getVoicing()));
		line(out, indent, "}");
	}

	/** Appends the envelope definition that produced an envelope filter. */
	private static void appendEnvelope(StringBuilder out, ParameterizedEnvelope envelope, int indent) {
		if (envelope instanceof ParameterizedVolumeEnvelope volume) {
			line(out, indent, "volume_envelope " + nameOrNone(volume.getMode()) + " {");
			appendAdsr(out, volume, indent + 1);
			line(out, indent, "}");
		} else if (envelope instanceof ParameterizedFilterEnvelope filter) {
			line(out, indent, "filter_envelope " + nameOrNone(filter.getMode()) + " {");
			appendAdsr(out, filter, indent + 1);
			line(out, indent, "}");
		} else if (envelope instanceof ParameterizedLayerEnvelope layer) {
			line(out, indent, "layer_envelope " + layer.getLayer() + " {");
			line(out, indent + 1, "attack " + function(layer.getAttackSelection()));
			line(out, indent + 1, "sustain " + function(layer.getSustainSelection()));
			line(out, indent + 1, "release " + function(layer.getReleaseSelection()));
			line(out, indent + 1, "volume0 " + functions(layer.getVolume0()));
			line(out, indent + 1, "volume1 " + functions(layer.getVolume1()));
			line(out, indent + 1, "volume2 " + functions(layer.getVolume2()));
			line(out, indent + 1, "volume3 " + functions(layer.getVolume3()));
			line(out, indent, "}");
		} else {
			throw unsupported("envelope", envelope);
		}
	}

	/** Appends the four ADSR selection functions of a volume or filter envelope. */
	private static void appendAdsr(StringBuilder out, ParameterizedEnvelopeAdapter envelope, int indent) {
		line(out, indent, "attack " + function(envelope.getAttackSelection()));
		line(out, indent, "decay " + function(envelope.getDecaySelection()));
		line(out, indent, "sustain " + function(envelope.getSustainSelection()));
		line(out, indent, "release " + function(envelope.getReleaseSelection()));
	}

	/** Appends {@code text} at the given indent level, followed by a newline. */
	private static void line(StringBuilder out, int indent, String text) {
		out.append(INDENT.repeat(indent)).append(text).append('\n');
	}

	/** Returns the name of an enum constant, or {@code none} when it is null. */
	private static String nameOrNone(Enum<?> value) {
		return value == null ? NONE : value.name();
	}

	/** Returns a finite double written so that {@link Double#parseDouble} restores it exactly. */
	private static String num(double value) {
		return Double.toString(value);
	}

	/** Returns the dimensions of a shape as a bracketed list, e.g. {@code [2, 3]}. */
	private static String shapeDims(TraversalPolicy shape) {
		StringBuilder out = new StringBuilder("[");
		for (int i = 0; i < shape.getDimensions(); i++) {
			if (i > 0) out.append(", ");
			out.append(shape.length(i));
		}
		return out.append(']').toString();
	}

	/** Returns a list of doubles as a bracketed list. */
	private static String doubleList(List<Double> values) {
		StringBuilder out = new StringBuilder("[");
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) out.append(", ");
			out.append(num(values.get(i)));
		}
		return out.append(']').toString();
	}

	/** Returns an array of doubles as a bracketed list. */
	private static String doubleArray(double[] values) {
		StringBuilder out = new StringBuilder("[");
		for (int i = 0; i < values.length; i++) {
			if (i > 0) out.append(", ");
			out.append(num(values[i]));
		}
		return out.append(']').toString();
	}

	/** Returns a {@link ParameterSet} as {@code (x, y, z)}, or {@code none} when null. */
	private static String paramSet(ParameterSet params) {
		if (params == null) return NONE;
		return "(" + num(params.getX()) + ", " + num(params.getY()) + ", " + num(params.getZ()) + ")";
	}

	/** Returns a {@link ParameterFunction} as {@code (x, y, z, c)}, or {@code none} when null. */
	private static String function(ParameterFunction function) {
		if (function == null) return NONE;
		return "(" + num(function.getX()) + ", " + num(function.getY()) + ", "
				+ num(function.getZ()) + ", " + num(function.getC()) + ")";
	}

	/** Returns a {@link MultipleParameterFunction} as a bracketed list, or {@code none} when null. */
	private static String functions(MultipleParameterFunction functions) {
		if (functions == null) return NONE;
		List<ParameterFunction> list = functions.getFunctions();
		StringBuilder out = new StringBuilder("[");
		for (int i = 0; i < list.size(); i++) {
			if (i > 0) out.append(", ");
			out.append(function(list.get(i)));
		}
		return out.append(']').toString();
	}

	/** Creates the exception reported for a model type the format cannot represent. */
	private static IllegalArgumentException unsupported(String kind, Object value) {
		return new IllegalArgumentException("Cannot represent " + kind + " of type "
				+ value.getClass().getName() + " as text");
	}

	/** The kinds of token the {@link Tokenizer} produces. */
	private enum TokenType {
		/** An opening brace. */
		LBRACE,
		/** A closing brace. */
		RBRACE,
		/** An opening bracket. */
		LBRACKET,
		/** A closing bracket. */
		RBRACKET,
		/** An opening parenthesis. */
		LPAREN,
		/** A closing parenthesis. */
		RPAREN,
		/** A comma. */
		COMMA,
		/** A run of non-whitespace, non-punctuation characters (a keyword, enum name or number). */
		ATOM,
		/** The end of the input. */
		END
	}

	/** A single lexical token, carrying its source line and column for error reporting. */
	private static final class Token {
		/** The kind of this token. */
		private final TokenType type;
		/** The source text of this token (empty for {@link TokenType#END}). */
		private final String text;
		/** The one-based line this token begins on. */
		private final int line;
		/** The one-based column this token begins on. */
		private final int column;

		/**
		 * Creates a token.
		 *
		 * @param type   the kind of token
		 * @param text   the source text
		 * @param line   the one-based line
		 * @param column the one-based column
		 */
		private Token(TokenType type, String text, int line, int column) {
			this.type = type;
			this.text = text;
			this.line = line;
			this.column = column;
		}
	}

	/** Converts source text into a list of {@link Token}s, ending with a {@link TokenType#END}. */
	private static final class Tokenizer {
		/** Not instantiable; the single entry point is {@link #tokenize(String)}. */
		private Tokenizer() { }

		/**
		 * Tokenizes the given source text.
		 *
		 * @param text the source text
		 * @return the tokens, terminated by a {@link TokenType#END} token
		 */
		private static List<Token> tokenize(String text) {
			List<Token> tokens = new ArrayList<>();
			int line = 1;
			int column = 1;
			int i = 0;

			while (i < text.length()) {
				char c = text.charAt(i);

				if (c == '\n') {
					line++;
					column = 1;
					i++;
				} else if (Character.isWhitespace(c)) {
					column++;
					i++;
				} else if (c == '#') {
					while (i < text.length() && text.charAt(i) != '\n') {
						i++;
						column++;
					}
				} else {
					TokenType punctuation = punctuation(c);
					if (punctuation != null) {
						tokens.add(new Token(punctuation, Character.toString(c), line, column));
						i++;
						column++;
					} else {
						int start = i;
						int startColumn = column;
						while (i < text.length() && isAtomChar(text.charAt(i))) {
							i++;
							column++;
						}
						tokens.add(new Token(TokenType.ATOM, text.substring(start, i), line, startColumn));
					}
				}
			}

			tokens.add(new Token(TokenType.END, "", line, column));
			return tokens;
		}

		/** Returns the punctuation token type for {@code c}, or null if {@code c} is not punctuation. */
		private static TokenType punctuation(char c) {
			return switch (c) {
				case '{' -> TokenType.LBRACE;
				case '}' -> TokenType.RBRACE;
				case '[' -> TokenType.LBRACKET;
				case ']' -> TokenType.RBRACKET;
				case '(' -> TokenType.LPAREN;
				case ')' -> TokenType.RPAREN;
				case ',' -> TokenType.COMMA;
				default -> null;
			};
		}

		/** Returns whether {@code c} may appear within an {@link TokenType#ATOM} token. */
		private static boolean isAtomChar(char c) {
			return !Character.isWhitespace(c) && punctuation(c) == null && c != '#';
		}
	}

	/** A recursive-descent parser over the token stream produced by {@link Tokenizer}. */
	private static final class Parser {
		/** The tokens being parsed, terminated by a {@link TokenType#END} token. */
		private final List<Token> tokens;
		/** The index of the next token to consume. */
		private int position;

		/**
		 * Creates a parser over the given tokens.
		 *
		 * @param tokens the tokens to parse
		 */
		private Parser(List<Token> tokens) {
			this.tokens = tokens;
		}

		/**
		 * Parses a {@code pattern} block into an ordered list of elements.
		 *
		 * @return the parsed elements, in order
		 */
		private List<PatternElement> parsePattern() {
			expectAtom("pattern");
			expect(TokenType.LBRACE);
			List<PatternElement> pattern = new ArrayList<>();
			while (peek().type != TokenType.RBRACE) {
				pattern.add(parseElement());
			}
			expect(TokenType.RBRACE);
			return pattern;
		}

		/**
		 * Parses a single {@code element} block, applying constructor defaults for omitted fields.
		 *
		 * @return the parsed element
		 */
		private PatternElement parseElement() {
			expectAtom("element");
			expect(TokenType.LBRACE);

			PatternElement element = new PatternElement();
			Set<String> seen = new TreeSet<>();
			Set<String> voicings = new TreeSet<>();

			while (peek().type != TokenType.RBRACE) {
				Token keyword = expect(TokenType.ATOM);
				switch (keyword.text) {
					case "position" -> {
						once(seen, keyword);
						element.setPosition(parseNumber());
					}
					case "duration" -> {
						once(seen, keyword);
						element.setDurationStrategy(parseEnumOrNone(NoteDurationStrategy.class));
						element.setNoteDurationSelection(parseNumber());
					}
					case "scale" -> {
						once(seen, keyword);
						element.setScaleTraversalStrategy(parseEnumOrNone(ScaleTraversalStrategy.class));
						element.setScalePosition(parseDoubleList());
					}
					case "direction" -> {
						once(seen, keyword);
						element.setDirection(parseEnumOrNone(PatternDirection.class));
					}
					case "repeat" -> {
						once(seen, keyword);
						element.setRepeatCount(parseInt());
						expectAtom("every");
						element.setRepeatDuration(parseNumber());
					}
					case "automation" -> {
						once(seen, keyword);
						element.setAutomationParameters(parseAutomation());
					}
					case "note" -> parseNoteEntry(element, voicings);
					default -> throw error(keyword, "unknown element field '" + keyword.text + "'");
				}
			}

			expect(TokenType.RBRACE);
			return element;
		}

		/**
		 * Parses a {@code note} entry (voicing plus note body or {@code none}) into the element.
		 *
		 * @param element  the element to add the note to
		 * @param voicings the voicings already seen in this element, for duplicate detection
		 */
		private void parseNoteEntry(PatternElement element, Set<String> voicings) {
			Token voicingToken = expect(TokenType.ATOM);
			ChannelInfo.Voicing voicing = asEnumOrNone(ChannelInfo.Voicing.class, voicingToken);
			if (!voicings.add(voicingToken.text)) {
				throw error(voicingToken, "duplicate note voicing '" + voicingToken.text + "'");
			}

			if (consume(NONE)) {
				element.setNote(voicing, null);
				return;
			}

			expect(TokenType.LBRACE);
			element.setNote(voicing, parseNoteBody());
			expect(TokenType.RBRACE);
		}

		/**
		 * Parses the body of a {@link PatternNote} up to (not including) its closing brace.
		 *
		 * @return the parsed note
		 */
		private PatternNote parseNoteBody() {
			Set<String> seen = new TreeSet<>();
			Double aggregation = null;
			List<PatternNoteAudio> layers = null;
			boolean layerMode = false;
			PatternNoteAudio delegate = null;
			NoteAudioFilter filter = null;
			boolean delegateMode = false;

			while (peek().type != TokenType.RBRACE) {
				Token keyword = expect(TokenType.ATOM);
				switch (keyword.text) {
					case "aggregation" -> {
						once(seen, keyword);
						aggregation = parseNumber();
					}
					case "choices" -> {
						once(seen, keyword);
						layerMode = true;
						layers = new ArrayList<>();
						for (double selection : parseDoubleArray()) {
							layers.add(new PatternNoteAudioChoice(selection));
						}
					}
					case "layers" -> {
						once(seen, keyword);
						layerMode = true;
						layers = parseAudioList();
					}
					case "delegate" -> {
						once(seen, keyword);
						delegateMode = true;
						delegate = parseDelegate();
					}
					case "filter" -> {
						once(seen, keyword);
						delegateMode = true;
						filter = parseFilter();
					}
					default -> throw error(keyword, "unknown note field '" + keyword.text + "'");
				}
			}

			if (layerMode && delegateMode) {
				throw error(peek(), "a note cannot have both layers and a delegate");
			}

			PatternNote note = layerMode ? new PatternNote(layers) : new PatternNote(delegate, filter);
			if (aggregation != null) note.setAggregationChoice(aggregation);
			return note;
		}

		/**
		 * Parses a bracketed list of {@link PatternNoteAudio} sources.
		 *
		 * @return the parsed sources, in order
		 */
		private List<PatternNoteAudio> parseAudioList() {
			List<PatternNoteAudio> audio = new ArrayList<>();
			expect(TokenType.LBRACKET);
			while (peek().type != TokenType.RBRACKET) {
				audio.add(parseAudio());
			}
			expect(TokenType.RBRACKET);
			return audio;
		}

		/**
		 * Parses one {@link PatternNoteAudio} source.
		 *
		 * @return the parsed source
		 */
		private PatternNoteAudio parseAudio() {
			Token keyword = expect(TokenType.ATOM);
			switch (keyword.text) {
				case "choice" -> {
					return new PatternNoteAudioChoice(parseNumber());
				}
				case "note" -> {
					expect(TokenType.LBRACE);
					PatternNote note = parseNoteBody();
					expect(TokenType.RBRACE);
					return note;
				}
				case "layer" -> {
					return parseLayer();
				}
				default -> throw error(keyword, "unknown note audio '" + keyword.text + "'");
			}
		}

		/**
		 * Parses a {@code layer} block into a {@link PatternNoteLayer}.
		 *
		 * @return the parsed layer
		 */
		private PatternNoteLayer parseLayer() {
			expect(TokenType.LBRACE);
			Set<String> seen = new TreeSet<>();
			PatternNoteAudio delegate = null;
			NoteAudioFilter filter = null;

			while (peek().type != TokenType.RBRACE) {
				Token keyword = expect(TokenType.ATOM);
				switch (keyword.text) {
					case "delegate" -> {
						once(seen, keyword);
						delegate = parseDelegate();
					}
					case "filter" -> {
						once(seen, keyword);
						filter = parseFilter();
					}
					default -> throw error(keyword, "unknown layer field '" + keyword.text + "'");
				}
			}

			expect(TokenType.RBRACE);
			return PatternNoteLayer.create(delegate, filter);
		}

		/**
		 * Parses a {@code delegate} wrapper: a braced audio source, or {@code none}.
		 *
		 * @return the parsed source, or null
		 */
		private PatternNoteAudio parseDelegate() {
			if (consume(NONE)) return null;
			expect(TokenType.LBRACE);
			PatternNoteAudio audio = parseAudio();
			expect(TokenType.RBRACE);
			return audio;
		}

		/**
		 * Parses a {@code filter} wrapper: a braced envelope filter, or {@code none}.
		 *
		 * @return the parsed filter, or null
		 */
		private NoteAudioFilter parseFilter() {
			if (consume(NONE)) return null;
			expect(TokenType.LBRACE);
			NoteAudioFilter filter = parseFilterBody();
			expect(TokenType.RBRACE);
			return filter;
		}

		/**
		 * Parses the body of a filter: an envelope definition plus its parameters and voicing.
		 *
		 * @return the reconstructed filter
		 */
		private NoteAudioFilter parseFilterBody() {
			Set<String> seen = new TreeSet<>();
			ParameterizedEnvelope envelope = null;
			ParameterSet params = null;
			ChannelInfo.Voicing voicing = null;

			while (peek().type != TokenType.RBRACE) {
				Token keyword = expect(TokenType.ATOM);
				switch (keyword.text) {
					case "volume_envelope" -> {
						once(seen, "envelope", keyword);
						envelope = parseVolumeEnvelope();
					}
					case "filter_envelope" -> {
						once(seen, "envelope", keyword);
						envelope = parseFilterEnvelope();
					}
					case "layer_envelope" -> {
						once(seen, "envelope", keyword);
						envelope = parseLayerEnvelope();
					}
					case "params" -> {
						once(seen, keyword);
						params = parseParamSetOrNone();
					}
					case "voicing" -> {
						once(seen, keyword);
						voicing = parseEnumOrNone(ChannelInfo.Voicing.class);
					}
					default -> throw error(keyword, "unknown filter field '" + keyword.text + "'");
				}
			}

			if (envelope == null) {
				throw error(peek(), "filter has no envelope");
			}

			return envelope.createFilter(params, voicing);
		}

		/**
		 * Parses a {@code volume_envelope} definition.
		 *
		 * @return the parsed envelope
		 */
		private ParameterizedVolumeEnvelope parseVolumeEnvelope() {
			ParameterizedVolumeEnvelope.Mode mode = parseEnumOrNone(ParameterizedVolumeEnvelope.Mode.class);
			ParameterFunction[] adsr = parseAdsr();
			return new ParameterizedVolumeEnvelope(mode, adsr[0], adsr[1], adsr[2], adsr[3]);
		}

		/**
		 * Parses a {@code filter_envelope} definition.
		 *
		 * @return the parsed envelope
		 */
		private ParameterizedFilterEnvelope parseFilterEnvelope() {
			ParameterizedFilterEnvelope.Mode mode = parseEnumOrNone(ParameterizedFilterEnvelope.Mode.class);
			ParameterFunction[] adsr = parseAdsr();
			return new ParameterizedFilterEnvelope(mode, adsr[0], adsr[1], adsr[2], adsr[3]);
		}

		/**
		 * Parses the four ADSR selection functions shared by volume and filter envelopes.
		 *
		 * @return the attack, decay, sustain and release functions, in that order
		 */
		private ParameterFunction[] parseAdsr() {
			expect(TokenType.LBRACE);
			Set<String> seen = new TreeSet<>();
			ParameterFunction attack = null;
			ParameterFunction decay = null;
			ParameterFunction sustain = null;
			ParameterFunction release = null;

			while (peek().type != TokenType.RBRACE) {
				Token keyword = expect(TokenType.ATOM);
				switch (keyword.text) {
					case "attack" -> {
						once(seen, keyword);
						attack = parseFunctionOrNone();
					}
					case "decay" -> {
						once(seen, keyword);
						decay = parseFunctionOrNone();
					}
					case "sustain" -> {
						once(seen, keyword);
						sustain = parseFunctionOrNone();
					}
					case "release" -> {
						once(seen, keyword);
						release = parseFunctionOrNone();
					}
					default -> throw error(keyword, "unknown envelope field '" + keyword.text + "'");
				}
			}

			expect(TokenType.RBRACE);
			return new ParameterFunction[] { attack, decay, sustain, release };
		}

		/**
		 * Parses a {@code layer_envelope} definition into a {@link ParameterizedLayerEnvelope}.
		 *
		 * @return the parsed envelope for the stated layer
		 */
		private ParameterizedLayerEnvelope parseLayerEnvelope() {
			int layer = parseInt();
			expect(TokenType.LBRACE);
			Set<String> seen = new TreeSet<>();
			ParameterFunction attack = null;
			ParameterFunction sustain = null;
			ParameterFunction release = null;
			MultipleParameterFunction[] volume = new MultipleParameterFunction[4];

			while (peek().type != TokenType.RBRACE) {
				Token keyword = expect(TokenType.ATOM);
				switch (keyword.text) {
					case "attack" -> {
						once(seen, keyword);
						attack = parseFunctionOrNone();
					}
					case "sustain" -> {
						once(seen, keyword);
						sustain = parseFunctionOrNone();
					}
					case "release" -> {
						once(seen, keyword);
						release = parseFunctionOrNone();
					}
					case "volume0" -> {
						once(seen, keyword);
						volume[0] = parseFunctionsOrNone();
					}
					case "volume1" -> {
						once(seen, keyword);
						volume[1] = parseFunctionsOrNone();
					}
					case "volume2" -> {
						once(seen, keyword);
						volume[2] = parseFunctionsOrNone();
					}
					case "volume3" -> {
						once(seen, keyword);
						volume[3] = parseFunctionsOrNone();
					}
					default -> throw error(keyword, "unknown layer envelope field '" + keyword.text + "'");
				}
			}

			expect(TokenType.RBRACE);
			return new ParameterizedEnvelopeLayers(attack, sustain, release,
					volume[0], volume[1], volume[2], volume[3]).getEnvelope(layer);
		}

		/**
		 * Parses the automation of an element: a shape followed by its values.
		 *
		 * @return the reconstructed automation collection
		 */
		private PackedCollection parseAutomation() {
			int[] dims = parseIntArray();
			double[] values = parseDoubleArray();

			TraversalPolicy shape = new TraversalPolicy(dims);
			if (shape.getTotalSize() != values.length) {
				throw error(peek(), "automation shape " + shape.getTotalSize()
						+ " does not match " + values.length + " values");
			}

			PackedCollection automation = new PackedCollection(values.length);
			automation.set(0, values);
			return automation.reshape(shape);
		}

		/**
		 * Parses a {@link ParameterSet} as {@code (x, y, z)}, or returns null for {@code none}.
		 *
		 * @return the parsed parameter set, or null
		 */
		private ParameterSet parseParamSetOrNone() {
			if (consume(NONE)) return null;
			expect(TokenType.LPAREN);
			double x = parseNumber();
			expect(TokenType.COMMA);
			double y = parseNumber();
			expect(TokenType.COMMA);
			double z = parseNumber();
			expect(TokenType.RPAREN);
			return new ParameterSet(x, y, z);
		}

		/**
		 * Parses a {@link ParameterFunction} as {@code (x, y, z, c)}, or returns null for {@code none}.
		 *
		 * @return the parsed function, or null
		 */
		private ParameterFunction parseFunctionOrNone() {
			if (consume(NONE)) return null;
			expect(TokenType.LPAREN);
			double x = parseNumber();
			expect(TokenType.COMMA);
			double y = parseNumber();
			expect(TokenType.COMMA);
			double z = parseNumber();
			expect(TokenType.COMMA);
			double c = parseNumber();
			expect(TokenType.RPAREN);
			return new ParameterFunction(x, y, z, c);
		}

		/**
		 * Parses a {@link MultipleParameterFunction} as a bracketed list, or returns null for {@code none}.
		 *
		 * @return the parsed functions, or null
		 */
		private MultipleParameterFunction parseFunctionsOrNone() {
			if (consume(NONE)) return null;
			List<ParameterFunction> list = new ArrayList<>();
			expect(TokenType.LBRACKET);
			while (peek().type != TokenType.RBRACKET) {
				if (!list.isEmpty()) expect(TokenType.COMMA);
				expect(TokenType.LPAREN);
				double x = parseNumber();
				expect(TokenType.COMMA);
				double y = parseNumber();
				expect(TokenType.COMMA);
				double z = parseNumber();
				expect(TokenType.COMMA);
				double c = parseNumber();
				expect(TokenType.RPAREN);
				list.add(new ParameterFunction(x, y, z, c));
			}
			expect(TokenType.RBRACKET);
			MultipleParameterFunction functions = new MultipleParameterFunction();
			functions.setFunctions(list);
			return functions;
		}

		/**
		 * Parses a bracketed list of doubles into a {@link List}.
		 *
		 * @return the parsed values, in order
		 */
		private List<Double> parseDoubleList() {
			List<Double> values = new ArrayList<>();
			for (double value : parseDoubleArray()) {
				values.add(value);
			}
			return values;
		}

		/**
		 * Parses a bracketed list of doubles into an array.
		 *
		 * @return the parsed values, in order
		 */
		private double[] parseDoubleArray() {
			List<Double> values = new ArrayList<>();
			expect(TokenType.LBRACKET);
			while (peek().type != TokenType.RBRACKET) {
				if (!values.isEmpty()) expect(TokenType.COMMA);
				values.add(parseNumber());
			}
			expect(TokenType.RBRACKET);
			return values.stream().mapToDouble(Double::doubleValue).toArray();
		}

		/**
		 * Parses a bracketed list of integers into an array.
		 *
		 * @return the parsed values, in order
		 */
		private int[] parseIntArray() {
			List<Integer> values = new ArrayList<>();
			expect(TokenType.LBRACKET);
			while (peek().type != TokenType.RBRACKET) {
				if (!values.isEmpty()) expect(TokenType.COMMA);
				values.add(parseInt());
			}
			expect(TokenType.RBRACKET);
			return values.stream().mapToInt(Integer::intValue).toArray();
		}

		/**
		 * Parses an atom as a double, failing with a located error when it is not a number.
		 *
		 * @return the parsed value
		 */
		private double parseNumber() {
			Token token = expect(TokenType.ATOM);
			try {
				return Double.parseDouble(token.text);
			} catch (NumberFormatException e) {
				throw error(token, "expected a number but found '" + token.text + "'");
			}
		}

		/**
		 * Parses an atom as an integer, failing with a located error when it is not an integer.
		 *
		 * @return the parsed value
		 */
		private int parseInt() {
			Token token = expect(TokenType.ATOM);
			try {
				return Integer.parseInt(token.text);
			} catch (NumberFormatException e) {
				throw error(token, "expected an integer but found '" + token.text + "'");
			}
		}

		/**
		 * Parses an atom as a constant of the given enum, or {@code none} as null.
		 *
		 * @param type the enum whose constant names are accepted
		 * @param <E>  the enum type
		 * @return the named constant, or null for {@code none}
		 */
		private <E extends Enum<E>> E parseEnumOrNone(Class<E> type) {
			return asEnumOrNone(type, expect(TokenType.ATOM));
		}

		/**
		 * Interprets an atom token as a constant of the given enum, or {@code none} as null.
		 *
		 * @param type  the enum whose constant names are accepted
		 * @param token the token to interpret
		 * @param <E>   the enum type
		 * @return the named constant, or null for {@code none}
		 */
		private <E extends Enum<E>> E asEnumOrNone(Class<E> type, Token token) {
			if (NONE.equals(token.text)) return null;
			try {
				return Enum.valueOf(type, token.text);
			} catch (IllegalArgumentException e) {
				throw error(token, "'" + token.text + "' is not a value of "
						+ type.getSimpleName());
			}
		}

		/**
		 * Records a keyword as seen, failing with a located error if it already was.
		 *
		 * @param seen    the set of fields already seen in the current block
		 * @param keyword the keyword token
		 */
		private void once(Set<String> seen, Token keyword) {
			once(seen, keyword.text, keyword);
		}

		/**
		 * Records {@code name} as seen, failing with a located error if it already was.
		 *
		 * @param seen    the set of fields already seen in the current block
		 * @param name    the field name to record (may differ from the keyword text)
		 * @param keyword the keyword token, for locating the error
		 */
		private void once(Set<String> seen, String name, Token keyword) {
			if (!seen.add(name)) {
				throw error(keyword, "duplicate field '" + keyword.text + "'");
			}
		}

		/**
		 * Consumes the next token if it is the given atom, returning whether it was consumed.
		 *
		 * @param atom the atom text to match
		 * @return whether a matching atom was consumed
		 */
		private boolean consume(String atom) {
			if (peek().type == TokenType.ATOM && peek().text.equals(atom)) {
				position++;
				return true;
			}
			return false;
		}

		/**
		 * Returns the next token without consuming it.
		 *
		 * @return the next token
		 */
		private Token peek() {
			return tokens.get(position);
		}

		/**
		 * Consumes and returns a token of the expected type, or fails with a located error.
		 *
		 * @param type the expected token type
		 * @return the consumed token
		 */
		private Token expect(TokenType type) {
			Token token = peek();
			if (token.type != type) {
				throw error(token, "expected " + type + " but found " + describe(token));
			}
			position++;
			return token;
		}

		/**
		 * Consumes an atom with the expected text, or fails with a located error.
		 *
		 * @param text the expected atom text
		 */
		private void expectAtom(String text) {
			Token token = expect(TokenType.ATOM);
			if (!token.text.equals(text)) {
				throw error(token, "expected '" + text + "' but found '" + token.text + "'");
			}
		}

		/** Fails unless all tokens have been consumed (only the {@link TokenType#END} remains). */
		private void expectEnd() {
			if (peek().type != TokenType.END) {
				throw error(peek(), "unexpected trailing input " + describe(peek()));
			}
		}

		/**
		 * Returns a human-readable description of a token for an error message.
		 *
		 * @param token the token to describe
		 * @return the description
		 */
		private String describe(Token token) {
			return token.type == TokenType.END ? "end of input" : "'" + token.text + "'";
		}

		/**
		 * Creates a {@link ParseException} located at the given token.
		 *
		 * @param token   the token the error is located at
		 * @param message the reason parsing failed
		 * @return the exception
		 */
		private ParseException error(Token token, String message) {
			return new ParseException(message, token.line, token.column);
		}
	}

	/**
	 * Thrown when text handed to {@link PatternText#parse(String)} or
	 * {@link PatternText#parseElement(String)} is not well-formed. The message names the
	 * line and column where parsing failed.
	 */
	public static final class ParseException extends IllegalArgumentException {
		/** The one-based line where parsing failed. */
		private final int line;
		/** The one-based column where parsing failed. */
		private final int column;

		/**
		 * Creates a parse exception located at the given line and column.
		 *
		 * @param message the reason parsing failed
		 * @param line    the one-based line
		 * @param column  the one-based column
		 */
		public ParseException(String message, int line, int column) {
			super(message + " (line " + line + ", column " + column + ")");
			this.line = line;
			this.column = column;
		}

		/** Returns the one-based line where parsing failed. */
		public int getLine() {
			return line;
		}

		/** Returns the one-based column where parsing failed. */
		public int getColumn() {
			return column;
		}
	}
}
