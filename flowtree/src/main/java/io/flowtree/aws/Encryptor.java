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

public class Encryptor implements EncryptionMaterialsProvider, AWSCredentialsProvider {
    public static final boolean enable509 = false;
    public static final boolean enableS3 = false;

    private KMSEngine kms;

    private final String myAccessKeyId;
	private final String mySecretKey;

    private final EncryptionMaterials ency;

    {
        if (enable509) {
            byte[] bytes = null;

            // 1. Load keys from files
            try {
                bytes = FileUtils.readFileToByteArray(new File(
                        "private.key"));
            } catch (IOException e) {
                e.printStackTrace();
            }

            KeyFactory kf = null;
            try {
                kf = KeyFactory.getInstance("RSA");
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }

            PKCS8EncodedKeySpec ks = bytes == null ? null : new PKCS8EncodedKeySpec(bytes);
            PrivateKey pk = null;

            if (bytes != null) {
                try {
                    pk = kf.generatePrivate(ks);
                } catch (InvalidKeySpecException e) {
                    e.printStackTrace();
                }
            }

            try {
                bytes = FileUtils.readFileToByteArray(new File("public.key"));
            } catch (IOException e) {
                e.printStackTrace();
            }

            PublicKey publicKey = null;
            try {
                publicKey = bytes == null ? null : KeyFactory.getInstance("RSA").generatePublic(
                        new X509EncodedKeySpec(bytes));

                KeyPair loadedKeyPair = new KeyPair(publicKey, pk);

                EncryptionMaterials encryptionMaterials = new EncryptionMaterials(
                        loadedKeyPair);

                AmazonS3EncryptionClient encryptionClient = new AmazonS3EncryptionClient(
                        new ProfileCredentialsProvider(),
                        new StaticEncryptionMaterialsProvider(encryptionMaterials));
            } catch (InvalidKeySpecException e) {
                e.printStackTrace();
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
        } else {
            KeyPairGenerator keyGenerator = null;
            try {
                keyGenerator = KeyPairGenerator.getInstance("RSA");
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }

            keyGenerator.initialize(1024, new SecureRandom());
            KeyPair myKeyPair = keyGenerator.generateKeyPair();
            ency = new EncryptionMaterials(myKeyPair);
        }
    }

    private final ClientConfiguration cc = new ClientConfiguration();

    public Encryptor(String myAccessKeyId, String mySecretKey) throws NoSuchAlgorithmException {
        this.myAccessKeyId = myAccessKeyId;
        this.mySecretKey = mySecretKey;

        doCrypt("airflowtest", "test", "public.key");
    }

    public void doCrypt(String bucket, String key, String file)
            throws NoSuchAlgorithmException {
        this.kms = new KMSEngine(null,this);
        AmazonS3Encryption encryptionClient = new AmazonS3EncryptionClientBuilder()
                .withEncryptionMaterials(this)
                .withRegion(getRegion())
                .build();

        if (enableS3) {
            byte[] plaintext = "Flow Tree Test".getBytes();
            System.out.println("plaintext's length: " + plaintext.length);
            encryptionClient.putObject(bucket, key, new File(file));
        }
    }

    public KMSEngine getKMSEngine() {
        return kms;
    }

    // TODO  This needs to be loaded from elsewhere
    public String getKey() { return "arn:aws:kms:us-east-1:830543758886:key/03aa9b76-fb31-484f-9e58-9b9c9620843b"; }

    public String getRegion() { return AWSRegion.UsEast1.toString(); }

    @Override
    public AWSCredentials getCredentials() {
        return new BasicAWSCredentials(myAccessKeyId, mySecretKey);
    }

    @Override
    public void refresh() {
    }

    @Override
    public EncryptionMaterials getEncryptionMaterials(Map<String, String> map) {
        return getEncryptionMaterials();
    }

    @Override
    public EncryptionMaterials getEncryptionMaterials() {
        return this.ency;
    }

    protected ClientConfiguration getClientConfiguration() {
        return cc;
    }
}
