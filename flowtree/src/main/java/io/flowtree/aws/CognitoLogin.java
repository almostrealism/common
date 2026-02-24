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

public class CognitoLogin implements Login {
	private final AWSCognitoIdentityProvider c;
	private final AWSCredentialsProvider p;

	public CognitoLogin(Encryptor e, AWSCredentialsProvider p) {
		this.p = p;
		c = AWSCognitoIdentityProviderClientBuilder.standard().withRegion(Region.US_West_2.toString())
				.withCredentials(p).build();
	}

	public String getDefaultPool() {
		ListUserPoolsRequest req = new ListUserPoolsRequest();
		req.setMaxResults(10);
		ListUserPoolsResult r = c.listUserPools(req);

		for (UserPoolDescriptionType s : r.getUserPools()) {
			return s.getId();
		}

		return null;
	}

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
