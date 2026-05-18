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

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProvider;
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProviderClientBuilder;
import com.amazonaws.services.cognitoidp.model.AdminInitiateAuthRequest;
import com.amazonaws.services.cognitoidp.model.AdminInitiateAuthResult;
import com.amazonaws.services.cognitoidp.model.AuthFlowType;
import com.amazonaws.services.cognitoidp.model.ListUserPoolsRequest;
import com.amazonaws.services.cognitoidp.model.ListUserPoolsResult;
import com.amazonaws.services.cognitoidp.model.NotAuthorizedException;
import com.amazonaws.services.cognitoidp.model.UserPoolDescriptionType;
import com.amazonaws.services.s3.model.Region;
import org.almostrealism.auth.Login;

import java.security.SecureRandom;
import java.util.HashMap;

/**
 * An AWS Cognito-backed implementation of the {@link Login} interface that
 * validates user credentials against an Amazon Cognito User Pool.
 *
 * <p>The client is constructed for the {@code us-west-2} region. Password
 * verification uses the {@code ADMIN_NO_SRP_AUTH} flow against a hard-coded
 * Cognito app client id and authenticates against the {@code us-east-2} region.
 * The default pool is discovered dynamically by listing the first result from
 * {@link #getDefaultPool()}.
 *
 * @author  Michael Murray
 */
public class CognitoLogin implements Login {

	/** The AWS Cognito Identity Provider client used to manage user-pool operations. */
	private final AWSCognitoIdentityProvider c;

	/** The AWS credentials provider used to authenticate all Cognito API calls. */
	private final AWSCredentialsProvider p;

	/**
	 * Constructs a new {@link CognitoLogin} that will authenticate using the
	 * supplied credentials provider. The provided {@link Encryptor} is accepted
	 * for interface compatibility but is not directly used by this constructor.
	 *
	 * @param e the encryptor (unused by this constructor, reserved for future use)
	 * @param p AWS credentials provider used to authorise API calls
	 */
	public CognitoLogin(Encryptor e, AWSCredentialsProvider p) {
		this.p = p;
		c = AWSCognitoIdentityProviderClientBuilder.standard().withRegion(Region.US_West_2.toString())
				.withCredentials(p).build();
	}

	/**
	 * Returns the id of the first user pool visible to the configured credentials.
	 * The listing is limited to 10 results; the id of the very first pool found is
	 * returned. Returns {@code null} if no pools are available.
	 *
	 * @return the id of the first Cognito user pool, or {@code null} if none exist
	 */
	public String getDefaultPool() {
		ListUserPoolsRequest req = new ListUserPoolsRequest();
		req.setMaxResults(10);
		ListUserPoolsResult r = c.listUserPools(req);

		for (UserPoolDescriptionType s : r.getUserPools()) {
			return s.getId();
		}

		return null;
	}

	/**
	 * Verifies a username and password against the default Cognito user pool using
	 * the {@code ADMIN_NO_SRP_AUTH} authentication flow. A random SRP_A value is
	 * generated to satisfy the protocol requirement even though the server-side
	 * SRP computation is bypassed.
	 *
	 * @param user     the username to authenticate
	 * @param password the plaintext password to verify
	 * @return {@code true} if the credentials are accepted and a session token is
	 *         returned by Cognito; {@code false} if the credentials are rejected
	 */
	@Override
	public boolean checkPassword(String user, String password) {
		HashMap<String, String> map = new HashMap<>();
		map.put("USERNAME", user);
		map.put("PASSWORD", password);
//		map.put("SECRET_HASH", "bfr12irv0hg3bm56mp241h2erhdmahqbmskbnhbojq0f29i4eem");
		map.put("SRP_A", Long.toHexString(Math.abs(new SecureRandom().nextLong())));

		AdminInitiateAuthRequest req = new AdminInitiateAuthRequest();
//		InitiateAuthRequest req = new InitiateAuthRequest();
		req.setClientId("625gdqqdb6m2msvdolga4pis05");
		req.setUserPoolId(getDefaultPool());
		req.setAuthParameters(map);
		req.setClientMetadata(new HashMap<String, String>());
		req.setAuthFlow(AuthFlowType.ADMIN_NO_SRP_AUTH);

		// TODO  Load region from environment variable
		try {
			AdminInitiateAuthResult r = AWSCognitoIdentityProviderClientBuilder.standard()
					.withRegion(Region.US_East_2.toString()).withCredentials(p)
					.build().adminInitiateAuth(req);
			return r.getSession() != null;
		} catch (NotAuthorizedException e) {
			return false;
		}
	}
}
