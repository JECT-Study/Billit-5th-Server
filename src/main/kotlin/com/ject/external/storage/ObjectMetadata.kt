package com.ject.external.storage

data class ObjectMetadata(
    val contentLength: Long,
    val contentType: String,
    val etag: String,
)
