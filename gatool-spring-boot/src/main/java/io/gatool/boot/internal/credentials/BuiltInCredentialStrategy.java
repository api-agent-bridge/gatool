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

package io.gatool.boot.internal.credentials;

import io.gatool.boot.execution.ApiCredentialStrategy;

/**
 * A credential strategy GATool builds itself from {@code gatool.api.credentials}.
 *
 * <p>
 * The auto-configuration treats an application's own {@link ApiCredentialStrategy} bean
 * differently from the built-in ones: it says at startup that the bean replaces the
 * configured strategy, and it leaves the scope rule of a shared credential to the
 * application. This type is how it tells the two apart.
 *
 * @author Željko Kozina
 */
public interface BuiltInCredentialStrategy extends ApiCredentialStrategy {

}
