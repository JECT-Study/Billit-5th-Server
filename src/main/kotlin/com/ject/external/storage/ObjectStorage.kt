package com.ject.external.storage

import java.time.Duration

interface ObjectStorage {
    fun createUploadUrl(
        key: String,
        expiresIn: Duration,
    ): StorageAccessUrl

    fun createDownloadUrl(
        key: String,
        expiresIn: Duration,
    ): StorageAccessUrl

    fun head(key: String): ObjectMetadata

    fun delete(key: String)
}
