/*
 * Copyright 2018 Michael Murray
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

package io.flowtree.msg;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/**
 * {@link Externalizable} wrapper that encrypts or decrypts a byte array using
 * the configured PBE {@link Cipher}.
 *
 * <p>On write, the payload is padded to an 8-byte boundary (using
 * {@code (byte) -1} as the pad value) before encryption, so that the DES
 * block cipher's block-alignment requirement is always satisfied. On read,
 * the trailing {@code -1} bytes are stripped after decryption.</p>
 *
 * <p>This class is used exclusively by {@link NodeProxy#writeSecure} and the
 * reader loop in {@link NodeProxy#run()} when {@link NodeProxy#secure} is
 * {@code true}.</p>
 *
 * <p>Previously defined as a private static inner class of {@link NodeProxy}.
 * Extracted here so that {@code NodeProxy} stays within the 1500-line
 * file-length limit.  The shared decrypt cipher is obtained via the
 * package-accessible {@link NodeProxy#sInc} field.</p>
 */
class NodeProxyByteWrapper implements Externalizable {

	/** Serial version UID required by {@link Externalizable}. */
	private static final long serialVersionUID = 6512456536452755866L;

	/** The cipher used to encrypt (on write) or decrypt (on read) the payload. */
	private final transient Cipher c;

	/** The raw byte payload, which may be modified in-place during padding/truncation. */
	private byte[] b;

	/**
	 * No-argument constructor that uses the shared static decrypt cipher
	 * {@link NodeProxy#sInc}. Required by the {@link Externalizable} contract for
	 * deserialisation.
	 */
	public NodeProxyByteWrapper() { this(NodeProxy.sInc); }

	/**
	 * Constructs a {@code NodeProxyByteWrapper} with the given cipher and no initial payload.
	 *
	 * @param c  The cipher to use for encryption or decryption.
	 */
	public NodeProxyByteWrapper(Cipher c) { this.c = c; }

	/**
	 * Constructs a {@code NodeProxyByteWrapper} ready to encrypt and write the given bytes.
	 *
	 * @param c  The cipher to use for encryption.
	 * @param b  The plaintext payload to encrypt on write.
	 */
	public NodeProxyByteWrapper(Cipher c, byte[] b) { this.c = c; this.b = b; }

	/**
	 * Replaces the current payload with the given byte array.
	 *
	 * @param b  The new payload bytes.
	 */
	public void setBytes(byte[] b) { this.b = b; }

	/**
	 * Returns the current payload bytes. After a read, this contains the
	 * decrypted plaintext with any trailing pad bytes removed.
	 *
	 * @return  The plaintext payload.
	 */
	public byte[] getBytes() { return this.b; }

	/**
	 * Pads the payload to an 8-byte boundary, encrypts it, and writes the
	 * encrypted length followed by the encrypted bytes to {@code out}.
	 *
	 * @param out  The stream to write the encrypted data to.
	 * @throws IOException  If an I/O error occurs while writing.
	 */
	@Override
	public void writeExternal(ObjectOutput out) throws IOException {
		try {
			int div = 8 - (b.length % 8);

			if (div < 8) {
				byte[] temp = this.b;
				int l = temp.length;
				this.b = new byte[l + div];
				System.arraycopy(temp, 0, this.b, 0, l);
				for (int i = l; i < this.b.length; i++) this.b[i] = -1;

				if (Message.dverbose)
					System.out.println("NodeProxy.ByteWrapper: Padded message by " + div);
			}

			byte[] b = this.c.doFinal(this.b);
			out.writeInt(b.length);
			out.write(b);
		} catch (IllegalBlockSizeException e) {
			System.out.println("NodeProxy.ByteWrapper: Illegal block size (" + e.getMessage() + ")");
		} catch (BadPaddingException e) {
			System.out.println("NodeProxy.ByteWrapper: Bad padding (" + e.getMessage() + ")");
		}
	}

	/**
	 * Reads an encrypted byte block from {@code in}, decrypts it, and strips
	 * any trailing {@code -1} pad bytes, leaving the original plaintext payload
	 * in {@link #b}.
	 *
	 * @param in  The stream to read the encrypted data from.
	 * @throws IOException  If an I/O error occurs while reading.
	 */
	@Override
	public void readExternal(ObjectInput in) throws IOException {
		try {
			if (this.b == null) this.b = new byte[in.readInt()];
			in.readFully(this.b);
			this.b = this.c.doFinal(this.b);

			int i;
			i: for (i = 0; i < 8; i++) {
				if (this.b[this.b.length - i - 1] != -1) break;
			}

			if (i > 0) {
				byte[] temp = new byte[this.b.length - i];
				System.arraycopy(this.b, 0, temp, 0, temp.length);
				this.b = temp;

				if (Message.dverbose)
					System.out.println("NodeProxy.ByteWrapper: Truncated message by " + i);
			}
		} catch (IllegalBlockSizeException e) {
			System.out.println("NodeProxy.ByteWrapper: Illegal block size (" + e.getMessage() + ")");
		} catch (BadPaddingException e) {
			System.out.println("NodeProxy.ByteWrapper: Bad padding (" + e.getMessage() + ")");
		}
	}
}
