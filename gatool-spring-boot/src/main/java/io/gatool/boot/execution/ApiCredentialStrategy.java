/*
 * Copyright 2026-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatool.boot.execution;

import org.springframework.http.client.ClientHttpRequestInterceptor;

/**
 * How GATool authenticates to the GraphQL API on every remote call.
 *
 * <p>
 * A strategy is a Spring {@link ClientHttpRequestInterceptor} on GATool's own
 * {@code RestClient}: it sees each outbound request and adds the credential the API
 * expects, an {@code Authorization} header in most cases. The interface adds only a name,
 * so a bean of this type is recognised as the application's own strategy and takes the
 * place of every built-in one that {@code gatool.api.credentials.strategy} selects.
 *
 * <p>
 * The built-in strategies are a static header, client credentials and token exchange,
 * plus the caller's own token forwarded behind an unsafe switch. An application with an
 * API that authenticates in another way declares a bean:
 *
 * <pre>
 * &#64;Bean
 * ApiCredentialStrategy signedRequests(Signer signer) {
 *     return (request, body, execution) -&gt; {
 *         request.getHeaders().set("X-Signature", signer.sign(body));
 *         return execution.execute(request, body);
 *     };
 * }
 * </pre>
 *
 * @author Željko Kozina
 */
@FunctionalInterface
public interface ApiCredentialStrategy extends ClientHttpRequestInterceptor {

}
