package com.ject.external.storage.s3

import com.ject.external.storage.ObjectStorageException
import com.ject.external.storage.ObjectStorageFailureReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.time.Duration

class S3ObjectStorageTest :
    FunSpec({
        lateinit var client: S3Client
        lateinit var presigner: S3Presigner
        lateinit var storage: S3ObjectStorage

        beforeTest {
            client = mock(S3Client::class.java)
            presigner = mock(S3Presigner::class.java)
            storage = S3ObjectStorage(client, presigner, "test-bucket")
        }

        test("Presigned URL은 객체 키와 유효 기간을 보존하고 추가 인증 헤더를 요구하지 않는다") {
            // given
            val properties =
                S3StorageProperties(
                    endpoint = URI.create("http://localhost:9090"),
                    bucket = "test-bucket",
                    region = "ap-seoul-1",
                    accessKey = "test-access",
                    secretKey = "test-secret",
                )
            val key = "profiles/한글 a+b.png"
            S3StorageConfig().s3Presigner(properties).use { realPresigner ->
                val actualStorage = S3ObjectStorage(client, realPresigner, properties.bucket)

                // when
                val upload = actualStorage.createUploadUrl(key, Duration.ofMinutes(5))
                val download = actualStorage.createDownloadUrl(key, Duration.ofMinutes(5))

                // then
                for (access in listOf(upload, download)) {
                    val query =
                        access.url.rawQuery.split("&").associate {
                            val parts = it.split("=", limit = 2)
                            URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts[1], Charsets.UTF_8)
                        }
                    access.url.host shouldBe "localhost"
                    access.url.port shouldBe 9090
                    access.url.path shouldBe "/test-bucket/$key"
                    query["X-Amz-Expires"] shouldBe "300"
                    query["X-Amz-SignedHeaders"] shouldBe "host"
                    query["X-Amz-Credential"].shouldNotBeNull() shouldContain "/ap-seoul-1/s3/aws4_request"
                    access.toString() shouldNotContain "X-Amz-Signature"
                }
                verifyNoInteractions(client)
            }
        }

        test("오브젝트 스토리지 장애는 진단 정보와 원인 예외를 보존한다") {
            // given
            val original =
                S3Exception
                    .builder()
                    .statusCode(503)
                    .requestId("request-1")
                    .awsErrorDetails(
                        AwsErrorDetails
                            .builder()
                            .errorCode("ServiceUnavailable")
                            .errorMessage("private-message")
                            .build(),
                    ).build()
            doThrow(original).`when`(client).deleteObject(any(DeleteObjectRequest::class.java))

            // when
            val failure = shouldThrow<ObjectStorageException> { storage.delete("key") }

            // then
            failure.reason shouldBe ObjectStorageFailureReason.UNAVAILABLE
            failure.operation shouldBe "delete"
            failure.statusCode shouldBe 503
            failure.serviceCode shouldBe "ServiceUnavailable"
            failure.requestId shouldBe "request-1"
            failure.cause shouldBe original
            failure.toString() shouldNotContain "private-message"
        }

        test("오브젝트 스토리지의 권한 거절을 서버 설정 오류로 분류한다") {
            // given
            val original = S3Exception.builder().statusCode(403).build()
            doThrow(original).`when`(client).headObject(any(HeadObjectRequest::class.java))

            // when
            val failure = shouldThrow<ObjectStorageException> { storage.head("key") }

            // then
            failure.reason shouldBe ObjectStorageFailureReason.CONFIGURATION
            failure.cause shouldBe original
        }

        test("일반 404는 리소스 없음으로 분류하고 성공으로 처리하지 않는다") {
            // given
            val original = S3Exception.builder().statusCode(404).build()
            doThrow(original).`when`(client).headObject(any(HeadObjectRequest::class.java))

            // when
            val failure = shouldThrow<ObjectStorageException> { storage.head("key") }

            // then
            failure.reason shouldBe ObjectStorageFailureReason.NOT_FOUND
            failure.cause shouldBe original
        }

        test("버킷 없음 오류는 404여도 설정 오류로 분류한다") {
            // given
            val original =
                S3Exception
                    .builder()
                    .statusCode(404)
                    .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build())
                    .build()
            doThrow(original).`when`(client).headObject(any(HeadObjectRequest::class.java))

            // when
            val failure = shouldThrow<ObjectStorageException> { storage.head("key") }

            // then
            failure.reason shouldBe ObjectStorageFailureReason.CONFIGURATION
        }

        test("오브젝트 스토리지의 요청 제한은 일시적 장애로 분류한다") {
            // given
            val original = S3Exception.builder().statusCode(429).build()
            doThrow(original).`when`(client).deleteObject(any(DeleteObjectRequest::class.java))

            // when
            val failure = shouldThrow<ObjectStorageException> { storage.delete("key") }

            // then
            failure.reason shouldBe ObjectStorageFailureReason.UNAVAILABLE
        }

        test("서비스 오류 상세가 없어도 일시적 장애 상태를 분류한다") {
            // given
            val original = S3Exception.builder().statusCode(502).build()
            doThrow(original).`when`(client).headObject(any(HeadObjectRequest::class.java))

            // when
            val failure = shouldThrow<ObjectStorageException> { storage.head("key") }

            // then
            failure.reason shouldBe ObjectStorageFailureReason.UNAVAILABLE
            failure.serviceCode shouldBe null
            failure.cause shouldBe original
        }

        test("알 수 없는 서비스 오류를 일시적 장애로 단정하지 않는다") {
            // given
            val original =
                S3Exception
                    .builder()
                    .statusCode(400)
                    .awsErrorDetails(AwsErrorDetails.builder().errorCode("InvalidRequest").build())
                    .build()
            doThrow(original).`when`(client).deleteObject(any(DeleteObjectRequest::class.java))

            // when
            val failure = shouldThrow<ObjectStorageException> { storage.delete("key") }

            // then
            failure.reason shouldBe ObjectStorageFailureReason.UNKNOWN
        }

        test("연결 실패는 응답 상태 없이 원인 예외를 보존한다") {
            // given
            val connectionFailure = ConnectException("connection failed")
            val original = SdkClientException.create("transport failed", connectionFailure)
            doThrow(original).`when`(client).headObject(any(HeadObjectRequest::class.java))

            // when
            val failure = shouldThrow<ObjectStorageException> { storage.head("key") }

            // then
            failure.reason shouldBe ObjectStorageFailureReason.UNAVAILABLE
            failure.statusCode shouldBe null
            failure.cause shouldBe original
            failure.cause?.cause shouldBe connectionFailure
        }

        test("중첩된 원인의 타임아웃도 일시적 장애로 분류한다") {
            // given
            val timeout = SocketTimeoutException("read timed out")
            val original = SdkClientException.create("transport failed", IllegalStateException(timeout))
            doThrow(original).`when`(client).deleteObject(any(DeleteObjectRequest::class.java))

            // when
            val failure = shouldThrow<ObjectStorageException> { storage.delete("key") }

            // then
            failure.reason shouldBe ObjectStorageFailureReason.UNAVAILABLE
            failure.cause shouldBe original
        }

        test("연결 실패 근거가 없는 SDK 오류는 일시적 장애로 단정하지 않는다") {
            // given
            val original = SdkClientException.create("response parsing failed", IllegalStateException("invalid response"))
            doThrow(original).`when`(client).headObject(any(HeadObjectRequest::class.java))

            // when
            val failure = shouldThrow<ObjectStorageException> { storage.head("key") }

            // then
            failure.reason shouldBe ObjectStorageFailureReason.UNKNOWN
            failure.statusCode shouldBe null
            failure.cause shouldBe original
        }

        test("업로드와 다운로드 URL 발급 실패에서도 원인 예외와 작업 종류를 보존한다") {
            // given
            val original = SdkClientException.create("signing failed")
            doThrow(original).`when`(presigner).presignPutObject(any(PutObjectPresignRequest::class.java))
            doThrow(original).`when`(presigner).presignGetObject(any(GetObjectPresignRequest::class.java))

            // when
            val upload = shouldThrow<ObjectStorageException> { storage.createUploadUrl("key", Duration.ofMinutes(5)) }
            val download = shouldThrow<ObjectStorageException> { storage.createDownloadUrl("key", Duration.ofMinutes(5)) }

            // then
            upload.operation shouldBe "createUploadUrl"
            download.operation shouldBe "createDownloadUrl"
            upload.reason shouldBe ObjectStorageFailureReason.UNKNOWN
            download.reason shouldBe ObjectStorageFailureReason.UNKNOWN
            upload.cause shouldBe original
            download.cause shouldBe original
        }

        test("빈 객체 키와 허용 범위 밖의 유효 기간은 SDK 호출 전에 거절한다") {
            // given
            val key = "key"

            // when
            shouldThrow<IllegalArgumentException> { storage.createUploadUrl("", Duration.ofMinutes(5)) }
            shouldThrow<IllegalArgumentException> { storage.createDownloadUrl(key, Duration.ZERO) }
            shouldThrow<IllegalArgumentException> { storage.createUploadUrl(key, Duration.ofDays(8)) }
            shouldThrow<IllegalArgumentException> { storage.head(" ") }
            shouldThrow<IllegalArgumentException> { storage.delete("") }

            // then
            verifyNoInteractions(client, presigner)
        }
    })
