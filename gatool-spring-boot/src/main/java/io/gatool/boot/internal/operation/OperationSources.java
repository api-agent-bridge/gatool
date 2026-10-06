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

package io.gatool.boot.internal.operation;

import java.util.List;

import io.gatool.core.internal.operation.OperationProblem;
import io.gatool.core.internal.operation.OperationSource;

/**
 * The operation files that the configured locations hold.
 *
 * @param sources every distinct file, with the exposure types whose locations reach it
 * @param problems the problems with the locations and the files
 * @author Željko Kozina
 */
public record OperationSources(List<OperationSource> sources, List<OperationProblem> problems) {
}
