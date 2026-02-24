package io.almostrealism.code;

import java.io.IOException;
import java.io.Writer;
import java.util.function.Consumer;
import java.util.stream.IntStream;

public class FunctionalWriter extends Writer {
	private final Consumer<Character> out;
	public FunctionalWriter(Consumer<Character> out) {
		this.out = out;
	}

	@Override
	public void write(char[] cbuf, int off, int len) throws IOException {
		IntStream.range(off, off + len).mapToObj(i -> cbuf[i]).forEach(out);
	}

	@Override
	public void flush() throws IOException {

	}

	@Override
	public void close() throws IOException {

	}
}
