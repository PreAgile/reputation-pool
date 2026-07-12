/*
 * Copyright 2026 the reputation-pool authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.preagile.reputationpool.persistence;

/**
 * An unchecked wrapper for a {@link java.sql.SQLException} raised while persisting or loading a
 * snapshot. The {@link io.github.preagile.reputationpool.core.port.ResourceStore} contract is
 * declared in domain terms with no checked SQL exception, so the store surfaces database failures as
 * this runtime exception, always carrying the original {@code SQLException} as its cause.
 */
public final class PersistenceException extends RuntimeException {

    /**
     * @param message what operation failed
     * @param cause the underlying database exception
     */
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
