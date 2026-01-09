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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
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

public class KMSEngine {
    private static final SecureRandom srand = new SecureRandom();

    private final AWSKMSClient client;

    private final Encryptor c;

    private String str;

    public KMSEngine(InputStream commands, Encryptor c) {
        super();

        this.c = c;

        client = (AWSKMSClient) AWSKMSClient.builder().withRegion(c.getRegion())
                .withCredentials(c)
                .withClientConfiguration(c.getClientConfiguration())
                .build();

        EnableKeyRequest req = new EnableKeyRequest();
        req.setKeyId(c.getKey());

        EnableKeyResult r = client.enableKey(req);
        System.out.println(r.getSdkResponseMetadata());

        if (commands != null) {
            Thread t = new Thread(() -> {
                Scanner s = new Scanner(commands);

                while (true) {
                    this.setString(s.nextLine());
                    EncryptResult res = client.encrypt(new EncryptRequest().withPlaintext(next()));
                    DecryptResult result = client.decrypt(new DecryptRequest().withCiphertextBlob(res.getCiphertextBlob()));
                    System.out.println(result.getPlaintext());
                }
            });

            t.start();
        }
    }

    public AWSKMSClient getClient() { return client; }

    public ByteBuffer next() {
        Charset charset = StandardCharsets.UTF_8;
        CharsetEncoder encoder = charset.newEncoder();
        CharsetDecoder decoder = charset.newDecoder();
        try {
            return encoder.encode(CharBuffer.wrap(str));
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    private void setString(String s) {
        this.str = s;
    }

    public void doKeys(String keyDir) throws Exception {
        // Generate RSA key pair of 1024 bits
        KeyPair keypair = genKeyPair("RSA", 2048);
        // Save to file system
        saveKeyPair(keyDir, keypair);
        // Loads from file system
        KeyPair loaded = loadKeyPair(keyDir, "RSA");
    }

    public KeyPair genKeyPair(String algorithm, int bitLength)
            throws NoSuchAlgorithmException {
        KeyPairGenerator keyGenerator = KeyPairGenerator.getInstance(algorithm);
        keyGenerator.initialize(2048, srand);
        return keyGenerator.generateKeyPair();
    }

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
