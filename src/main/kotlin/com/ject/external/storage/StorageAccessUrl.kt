package com.ject.external.storage

import java.net.URI
import java.time.Instant

data class StorageAccessUrl(
    val url: URI,
    val expiresAt: Instant,
) {
    override fun toString(): String = "StorageAccessUrl(url=<redacted>, expiresAt=$expiresAt)"
}
