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

/**
 * AWS integration utilities for FlowTree, including Cognito-based authentication
 * and KMS-backed encryption.
 *
 * <p>The three classes in this package collaborate as follows:
 * <ul>
 *   <li>{@link io.flowtree.aws.Encryptor} — provides AWS credentials and RSA
 *       encryption materials for client-side S3 encryption.</li>
 *   <li>{@link io.flowtree.aws.KMSEngine} — wraps the AWS KMS client to
 *       perform envelope key operations and RSA key-pair management.</li>
 *   <li>{@link io.flowtree.aws.CognitoLogin} — validates user credentials
 *       against an Amazon Cognito User Pool using the
 *       {@code ADMIN_NO_SRP_AUTH} flow.</li>
 * </ul>
 *
 * @author  Michael Murray
 */
package io.flowtree.aws;
