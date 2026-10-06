// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * An origin with an explicit port (01 Networking baseline): "same origin" means lowercase
 * `scheme` + lowercase `host` + `port` (defaulted for http/https) all equal.
 * Constructed by `Origin.of(url)` inside `:core:net` — keep construction there so the
 * lowercase/port-default rules live next to the client islands.
 */
data class Origin(val scheme: String, val host: String, val port: Int)

/**
 * Feed-credential source injected only into `:core:net`'s `refresh` island (01 Networking
 * baseline). `:download:impl` is the single implementation (`FeedCredentialLookup`), reading the
 * DB on `Dispatchers.IO`. Bound with a qualified metro binding so no other island sees it;
 * `:sync:api` installs [None] and can never trigger a credential read.
 */
fun interface CredentialLookup {
    /** `Authorization: Basic …` header value for [origin], or `null` when none is stored. */
    fun basicAuthorization(origin: Origin): String?

    /** Waits for credential stores to finish loading; a no-op for [None]. */
    suspend fun awaitLoaded() {}

    companion object {
        /** No credentials — bound for `:sync:api`. */
        val None = CredentialLookup { null }
    }
}
