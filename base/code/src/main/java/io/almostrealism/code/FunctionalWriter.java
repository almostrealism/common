package io.almostrealism.code;

import java.io.IOException;
import java.io.Writer;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * A {@link Writer} implementation that dispatches each character to a {@link Consumer}.
 *
 * <p>{@code FunctionalWriter} bridges the {@link Writer} API (which accepts {@code char[]}
 * buffers) with functional character-level consumers. It is used to redirect generated code
 * output into arbitrary destinations such as string builders or sinks.</p>
 */
public class FunctionalWriter extends Writer {
	/** The consumer that receives each character written to this writer. */
	private final Consumer<Character> out;

	/**
	 * Creates a new {@code FunctionalWriter} that dispatches characters to the given consumer.
	 *
	 * @param out the consumer to receive each written character
	 */
	public FunctionalWriter(Consumer<Character> out) {
		this.out = out;
	}

	/**
	 * Writes a range of characters from the given buffer by dispatching each character to the consumer.
	 *
	 * @param cbuf the source character buffer
	 * @param off the offset of the first character to write
	 * @param len the number of characters to write
	 * @throws IOException never thrown; declared for API compatibility
	 */
	@Override
	public void write(char[] cbuf, int off, int len) throws IOException {
		IntStream.range(off, off + len).mapToObj(i -> cbuf[i]).forEach(out);
	}

	/**
	 * Flushes the writer. This implementation is a no-op as the consumer is synchronous.
	 *
	 * @throws IOException never thrown; declared for API compatibility
	 */
	@Override
	public void flush() throws IOException {

	}

	/**
	 * Closes the writer. This implementation is a no-op as there are no resources to release.
	 *
	 * @throws IOException never thrown; declared for API compatibility
	 */
	@Override
	public void close() throws IOException {

	}
}
