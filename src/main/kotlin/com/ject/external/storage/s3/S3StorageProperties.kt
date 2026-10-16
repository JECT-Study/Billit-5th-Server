package com.ject.external.storage.s3

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

@ConfigurationProperties("app.storage.s3")
class S3StorageProperties(
    val endpoint: URI,
    val bucket: String,
    val region: String,
    val accessKey: String,
    val secretKey: String,
) {
    init {
        require(endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null) {
            "저장소 엔드포인트에 인증 정보, 쿼리 문자열 또는 프래그먼트를 포함할 수 없습니다."
        }
        require(endpoint.path.isNullOrEmpty() || endpoint.path == "/") { "저장소 엔드포인트에 경로를 포함할 수 없습니다." }
        require(bucket.isNotBlank()) { "저장소 버킷 이름은 비어 있거나 공백으로만 구성될 수 없습니다." }
    }
}
