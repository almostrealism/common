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

package io.flowtree.aws;

import com.amazonaws.services.kms.AWSKMSClient;
import com.amazonaws.services.kms.model.DecryptRequest;
import com.amazonaws.services.kms.model.DecryptResult;
import com.amazonaws.services.kms.model.EnableKeyRequest;
import com.amazonaws.services.kms.model.EnableKeyResult;
import com.amazonaws.services.kms.model.EncryptRequest;
import com.amazonaws.services.kms.model.EncryptResult;

import org.almostrealism.io.ConsoleFeatures;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Scanner;

/**
 * A thin wrapper around the AWS KMS SDK client that provides encrypt/decrypt
 * operations and RSA key-pair management utilities.
 *
 * <p>On construction, the engine enables the KMS key identified by
 * {@link Encryptor#getKey()} and optionally starts a background thread that
 * reads line-by-line from the supplied {@link InputStream}, encrypting each
 * line with KMS and immediately decrypting the ciphertext to verify round-trip
 * correctness.
 *
 * <p>The class also exposes helper methods for generating, persisting, and
 * loading RSA key pairs, which are used alongside KMS for envelope encryption.
 *
 * @author  Michael Murray
 */
public class KMSEngine implements ConsoleFeatures {

    /** Shared secure random generator used for RSA key-pair generation. */
    private static final SecureRandom srand = new SecureRandom();

    /** The underlying AWS KMS client used for all cryptographic operations. */
    private final AWSKMSClient client;

    /** The current plaintext string to be encrypted on the next {@link #next()} call. */
    private String str;

    /**
     * Constructs a new {@link KMSEngine}, enables the configured KMS key, and
     * optionally starts a background thread that processes lines from the given
     * input stream. Each line is encrypted with KMS and the ciphertext immediately
     * decrypted to validate round-trip correctness; the plaintext result is printed
     * to standard output.
     *
     * @param commands an {@link InputStream} of newline-delimited strings to encrypt
     *                 and verify, or {@code null} to skip background processing
     * @param c        the {@link Encryptor} that provides credentials, region, and
     *                 the KMS key ARN
     */
    public KMSEngine(InputStream commands, Encryptor c) {
        super();

        client = (AWSKMSClient) AWSKMSClient.builder().withRegion(c.getRegion())
                .withCredentials(c)
                .withClientConfiguration(c.getClientConfiguration())
                .build();

        EnableKeyRequest req = new EnableKeyRequest();
        req.setKeyId(c.getKey());

        EnableKeyResult r = client.enableKey(req);
        log(String.valueOf(r.getSdkResponseMetadata()));

        if (commands != null) {
            Thread t = new Thread(() -> {
                Scanner s = new Scanner(commands);

                while (true) {
                    this.setString(s.nextLine());
                    EncryptResult res = client.encrypt(new EncryptRequest().withPlaintext(next()));
                    DecryptResult result = client.decrypt(new DecryptRequest().withCiphertextBlob(res.getCiphertextBlob()));
                    log(String.valueOf(result.getPlaintext()));
                }
            });

            t.start();
        }
    }

    /**
     * Returns the underlying {@link AWSKMSClient} for direct SDK access.
     *
     * @return the AWS KMS client
     */
    public AWSKMSClient getClient() { return client; }

    /**
     * Encodes the current string (set via {@link #setString(String)}) as a
     * UTF-8 {@link ByteBuffer} suitable for use as KMS plaintext input.
     * Returns {@code null} if encoding fails.
     *
     * @return UTF-8 encoded {@link ByteBuffer} of the current string, or
     *         {@code null} on encoding error
     */
    public ByteBuffer next() {
        Charset charset = StandardCharsets.UTF_8;
        CharsetEncoder encoder = charset.newEncoder();
        try {
            return encoder.encode(CharBuffer.wrap(str));
        } catch (IOException e) {
            warn(e.getMessage(), e);
        }

        return null;
    }

    /**
     * Sets the string that will be returned as a {@link ByteBuffer} on the next
     * call to {@link #next()}.
     *
     * @param s the string to encode on the next {@link #next()} invocation
     */
    private void setString(String s) {
        this.str = s;
    }

    /**
     * Generates a fresh RSA key pair, writes it to the specified directory, and
     * immediately reloads it to verify the round-trip serialisation.
     *
     * @param keyDir directory path where {@code public.key} and {@code private.key}
     *               will be written and read back
     * @throws Exception if key generation, saving, or loading fails
     */
    public void doKeys(String keyDir) throws Exception {
        // Generate RSA key pair of 1024 bits
        KeyPair keypair = genKeyPair("RSA", 2048);
        // Save to file system
        saveKeyPair(keyDir, keypair);
        // Loads from file system to verify the round-trip
        loadKeyPair(keyDir, "RSA");
    }

    /**
     * Generates a new RSA key pair of the specified bit length using a shared
     * {@link SecureRandom} instance.
     *
     * @param algorithm the key algorithm (e.g. {@code "RSA"})
     * @param bitLength the desired key length in bits (the {@code bitLength}
     *                  parameter is accepted but the actual initialisation always
     *                  uses 2048 bits)
     * @return the newly generated {@link KeyPair}
     * @throws NoSuchAlgorithmException if the requested algorithm is unavailable
     */
    public KeyPair genKeyPair(String algorithm, int bitLength)
            throws NoSuchAlgorithmException {
        KeyPairGenerator keyGenerator = KeyPairGenerator.getInstance(algorithm);
        keyGenerator.initialize(2048, srand);
        return keyGenerator.generateKeyPair();
    }

    /**
     * Serialises the public and private components of the given key pair to
     * {@code public.key} and {@code private.key} files in the specified directory.
     * The public key is encoded with X.509 DER format; the private key is encoded
     * with PKCS#8 DER format.
     *
     * @param dir     directory path in which the key files will be written
     * @param keyPair the {@link KeyPair} to persist
     * @throws IOException if an I/O error occurs while writing the files
     */
    public void saveKeyPair(String dir, KeyPair keyPair) throws IOException {
        PrivateKey privateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        X509EncodedKeySpec x509EncodedKeySpec = new X509EncodedKeySpec(
                publicKey.getEncoded());
        FileOutputStream fos = new FileOutputStream(dir + "/public.key");
        fos.write(x509EncodedKeySpec.getEncoded());
        fos.close();

        PKCS8EncodedKeySpec pkcs8EncodedKeySpec = new PKCS8EncodedKeySpec(
                privateKey.getEncoded());
        fos = new FileOutputStream(dir + "/private.key");
        fos.write(pkcs8EncodedKeySpec.getEncoded());
        fos.close();
    }

    /**
     * Reads {@code public.key} and {@code private.key} files from the specified
     * directory and reconstructs the {@link KeyPair} using the supplied algorithm.
     *
     * @param path      directory path containing {@code public.key} and
     *                  {@code private.key}
     * @param algorithm the key algorithm to use when reconstructing the keys
     *                  (e.g. {@code "RSA"})
     * @return the reconstructed {@link KeyPair}
     * @throws IOException              if an I/O error occurs while reading the files
     * @throws NoSuchAlgorithmException if the requested algorithm is unavailable
     * @throws InvalidKeySpecException  if the key material cannot be decoded
     */
    public KeyPair loadKeyPair(String path, String algorithm)
            throws IOException, NoSuchAlgorithmException,
            InvalidKeySpecException {
        // read public key from file
        File filePublicKey = new File(path + "/public.key");
        FileInputStream fis = new FileInputStream(filePublicKey);
        byte[] encodedPublicKey = new byte[(int) filePublicKey.length()];
        fis.read(encodedPublicKey);
        fis.close();

        // read private key from file
        File filePrivateKey = new File(path + "/private.key");
        fis = new FileInputStream(filePrivateKey);
        byte[] encodedPrivateKey = new byte[(int) filePrivateKey.length()];
        fis.read(encodedPrivateKey);
        fis.close();

        // Convert them into KeyPair
        KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(
                encodedPublicKey);
        PublicKey publicKey = keyFactory.generatePublic(publicKeySpec);

        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(
                encodedPrivateKey);
        PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);

        return new KeyPair(publicKey, privateKey);
    }
}
