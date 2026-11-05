package io.almostrealism.persist;

import io.almostrealism.resource.IOStreams;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;

/**
 * Tests that {@link LocalResource} reproduces its content exactly when it is copied to a
 * local file or sent to a stream.
 */
public class LocalResourceTest extends TestSuiteBase {

	/** Content whose first byte is distinguishable from the rest. */
	private static final byte[] CONTENT = "ABCDEF".getBytes(StandardCharsets.US_ASCII);

	/**
	 * {@link LocalResource#saveLocal(String)} writes every byte of the source file,
	 * including the first, to the destination.
	 */
	@Test(timeout = 10000)
	public void saveLocalCopiesEveryByte() throws IOException {
		File source = Files.createTempFile("local-resource-source", ".bin").toFile();
		File destination = Files.createTempFile("local-resource-copy", ".bin").toFile();
		source.deleteOnExit();
		destination.deleteOnExit();
		Files.write(source.toPath(), CONTENT);

		new LocalResource(source.getAbsolutePath()).saveLocal(destination.getAbsolutePath());

		assertArrayEquals(CONTENT, Files.readAllBytes(destination.toPath()));
	}

	/**
	 * {@link LocalResource#send(IOStreams)} writes every byte of a stream supplied through
	 * {@link LocalResource#load(IOStreams)}, including the first, to the output stream.
	 */
	@Test(timeout = 10000)
	public void sendWritesEveryByte() throws IOException {
		LocalResource resource = new LocalResource();
		resource.load(new IOStreams(new ByteArrayInputStream(CONTENT)));

		ByteArrayOutputStream sent = new ByteArrayOutputStream();
		IOStreams destination = new IOStreams();
		destination.out = new DataOutputStream(sent);
		resource.send(destination);

		assertArrayEquals(CONTENT, sent.toByteArray());
	}
}
