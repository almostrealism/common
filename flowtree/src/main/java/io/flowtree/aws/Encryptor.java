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

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.costandusagereport.model.AWSRegion;
import com.amazonaws.services.s3.AmazonS3Encryption;
import com.amazonaws.services.s3.AmazonS3EncryptionClient;
import com.amazonaws.services.s3.AmazonS3EncryptionClientBuilder;
import com.amazonaws.services.s3.model.EncryptionMaterials;
import com.amazonaws.services.s3.model.EncryptionMaterialsProvider;
import com.amazonaws.services.s3.model.StaticEncryptionMaterialsProvider;
import org.almostrealism.io.ConsoleFeatures;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
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
import java.util.Map;

/**
 * Combines AWS credentials management with client-side S3 encryption
 * material provisioning. This class implements both
 * {@link EncryptionMaterialsProvider} and {@link AWSCredentialsProvider} so
 * that it can be passed directly to the Amazon S3 encryption client builder.
 *
 * <p>Two operational modes are controlled by the compile-time flags:
 * <ul>
 *   <li>{@link #enable509} — when {@code true}, RSA key material is loaded
 *       from {@code private.key} and {@code public.key} files on disk.  When
 *       {@code false} (the default), an ephemeral 1024-bit RSA key pair is
 *       generated at construction time.</li>
 *   <li>{@link #enableS3} — when {@code true}, the constructor uploads a test
 *       plaintext string to S3 using the encryption client.  When {@code false}
 *       (the default), S3 operations are skipped entirely.</li>
 * </ul>
 *
 * <p>A {@link KMSEngine} instance is created and associated with this encryptor
 * during {@link #doCrypt(String, String, String)}.
 *
 * @author  Michael Murray
 */
public class Encryptor implements EncryptionMaterialsProvider, AWSCredentialsProvider, ConsoleFeatures {

    /**
     * When {@code true}, RSA key material is loaded from {@code private.key} /
     * {@code public.key} files rather than generated at runtime.
     */
    public static final boolean enable509 = false;

    /**
     * When {@code true}, a test plaintext string is uploaded to S3 using the
     * encryption client during {@link #doCrypt(String, String, String)}.
     */
    public static final boolean enableS3 = false;

    /** AWS KMS engine used for key-management operations. */
    private KMSEngine kms;

    /** AWS access key id used for all API calls. */
    private final String myAccessKeyId;

    /** AWS secret access key used for all API calls. */
	private final String mySecretKey;

    /**
     * The RSA {@link EncryptionMaterials} used when wrapping S3 object keys.
     * Populated either from disk or from a freshly generated key pair depending
     * on {@link #enable509}.
     */
    private final EncryptionMaterials ency;

    // Instance initializer: sets up encryption materials based on the enable509 flag.
    {
        if (enable509) {
            byte[] bytes = null;

            // 1. Load keys from files
            try {
                bytes = FileUtils.readFileToByteArray(new File(
                        "private.key"));
            } catch (IOException e) {
                warn(e.getMessage(), e);
            }

            KeyFactory kf = null;
            try {
                kf = KeyFactory.getInstance("RSA");
            } catch (NoSuchAlgorithmException e) {
                warn(e.getMessage(), e);
            }

            PKCS8EncodedKeySpec ks = bytes == null ? null : new PKCS8EncodedKeySpec(bytes);
            PrivateKey pk = null;

            if (bytes != null) {
                try {
                    pk = kf.generatePrivate(ks);
                } catch (InvalidKeySpecException e) {
                    warn(e.getMessage(), e);
                }
            }

            try {
                bytes = FileUtils.readFileToByteArray(new File("public.key"));
            } catch (IOException e) {
                warn(e.getMessage(), e);
            }

            PublicKey publicKey = null;
            try {
                publicKey = bytes == null ? null : KeyFactory.getInstance("RSA").generatePublic(
                        new X509EncodedKeySpec(bytes));

                KeyPair loadedKeyPair = new KeyPair(publicKey, pk);

                EncryptionMaterials encryptionMaterials = new EncryptionMaterials(
                        loadedKeyPair);

                new AmazonS3EncryptionClient(
                        new ProfileCredentialsProvider(),
                        new StaticEncryptionMaterialsProvider(encryptionMaterials));
            } catch (InvalidKeySpecException e) {
                warn(e.getMessage(), e);
            } catch (NoSuchAlgorithmException e) {
                warn(e.getMessage(), e);
            }
        } else {
            KeyPairGenerator keyGenerator = null;
            try {
                keyGenerator = KeyPairGenerator.getInstance("RSA");
            } catch (NoSuchAlgorithmException e) {
                warn(e.getMessage(), e);
            }

            keyGenerator.initialize(1024, new SecureRandom());
            KeyPair myKeyPair = keyGenerator.generateKeyPair();
            ency = new EncryptionMaterials(myKeyPair);
        }
    }

    /** AWS SDK client configuration shared across API calls. */
    private final ClientConfiguration cc = new ClientConfiguration();

    /**
     * Constructs an {@link Encryptor} for the given AWS credentials. Immediately
     * invokes {@link #doCrypt(String, String, String)} to initialise the
     * {@link KMSEngine} and optionally upload a test object to S3.
     *
     * @param myAccessKeyId the AWS access key id
     * @param mySecretKey   the AWS secret access key
     * @throws NoSuchAlgorithmException if the RSA algorithm is unavailable
     */
    public Encryptor(String myAccessKeyId, String mySecretKey) throws NoSuchAlgorithmException {
        this.myAccessKeyId = myAccessKeyId;
        this.mySecretKey = mySecretKey;

        doCrypt("airflowtest", "test", "public.key");
    }

    /**
     * Initialises the {@link KMSEngine} and, when {@link #enableS3} is
     * {@code true}, uploads the specified file to S3 using client-side encryption.
     *
     * @param bucket         the target S3 bucket name
     * @param key            the target S3 object key
     * @param file           local file path to upload when {@link #enableS3} is enabled
     * @throws NoSuchAlgorithmException if the RSA algorithm is unavailable
     */
    public void doCrypt(String bucket, String key, String file)
            throws NoSuchAlgorithmException {
        this.kms = new KMSEngine(null,this);
        AmazonS3Encryption encryptionClient = new AmazonS3EncryptionClientBuilder()
                .withEncryptionMaterials(this)
                .withRegion(getRegion())
                .build();

        if (enableS3) {
            byte[] plaintext = "Flow Tree Test".getBytes();
            log(String.valueOf(plaintext.length));
            encryptionClient.putObject(bucket, key, new File(file));
        }
    }

    /**
     * Returns the {@link KMSEngine} associated with this encryptor. The engine
     * is created during {@link #doCrypt(String, String, String)}.
     *
     * @return the KMS engine, or {@code null} if not yet initialised
     */
    public KMSEngine getKMSEngine() {
        return kms;
    }

    /**
     * Returns the ARN of the KMS key used for envelope encryption. This value
     * is currently hard-coded and should be loaded from the environment or a
     * configuration source.
     *
     * @return the KMS key ARN string
     */
    // TODO  This needs to be loaded from elsewhere
    public String getKey() { return "arn:aws:kms:us-east-1:830543758886:key/03aa9b76-fb31-484f-9e58-9b9c9620843b"; }

    /**
     * Returns the AWS region string used for KMS and S3 operations.
     *
     * @return the region identifier string (e.g. {@code "us-east-1"})
     */
    public String getRegion() { return AWSRegion.UsEast1.toString(); }

    /**
     * Returns the {@link AWSCredentials} constructed from the access key id and
     * secret key provided at construction time.
     *
     * @return the AWS credentials
     */
    @Override
    public AWSCredentials getCredentials() {
        return new BasicAWSCredentials(myAccessKeyId, mySecretKey);
    }

    /**
     * Refreshes the credentials. This implementation is a no-op because
     * static credentials do not require refreshing.
     */
    @Override
    public void refresh() {
    }

    /**
     * Returns the {@link EncryptionMaterials} for a given material description
     * map by delegating to {@link #getEncryptionMaterials()}.
     *
     * @param map material description map (unused)
     * @return the configured encryption materials
     */
    @Override
    public EncryptionMaterials getEncryptionMaterials(Map<String, String> map) {
        return getEncryptionMaterials();
    }

    /**
     * Returns the RSA {@link EncryptionMaterials} used for client-side S3
     * encryption. The materials are generated once during instance initialisation.
     *
     * @return the encryption materials
     */
    @Override
    public EncryptionMaterials getEncryptionMaterials() {
        return this.ency;
    }

    /**
     * Returns the AWS SDK {@link ClientConfiguration} used when building service
     * clients from this encryptor.
     *
     * @return the client configuration
     */
    protected ClientConfiguration getClientConfiguration() {
        return cc;
    }
}
