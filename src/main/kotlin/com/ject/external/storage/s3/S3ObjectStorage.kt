package com.ject.external.storage.s3

import com.ject.external.storage.ObjectMetadata
import com.ject.external.storage.ObjectStorage
import com.ject.external.storage.ObjectStorageException
import com.ject.external.storage.ObjectStorageFailureReason
import com.ject.external.storage.StorageAccessUrl
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException
import software.amazon.awssdk.core.exception.ApiCallTimeoutException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration

class S3ObjectStorage(
    private val client: S3Client,
    private val presigner: S3Presigner,
    private val bucket: String,
) : ObjectStorage {
    override fun createUploadUrl(
        key: String,
        expiresIn: Duration,
    ): StorageAccessUrl {
        validateAccess(key, expiresIn)
        val request =
            PutObjectPresignRequest
                .builder()
                .signatureDuration(expiresIn)
                .putObjectRequest(
                    PutObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(key)
                        .build(),
                ).build()
        try {
            val signed = presigner.presignPutObject(request)
            return StorageAccessUrl(url = signed.url().toURI(), expiresAt = signed.expiration())
        } catch (exception: SdkClientException) {
            throw clientFailure("createUploadUrl", exception)
        }
    }

    override fun createDownloadUrl(
        key: String,
        expiresIn: Duration,
    ): StorageAccessUrl {
        validateAccess(key, expiresIn)
        val request =
            GetObjectPresignRequest
                .builder()
                .signatureDuration(expiresIn)
                .getObjectRequest(
                    GetObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(key)
                        .build(),
                ).build()
        try {
            val signed = presigner.presignGetObject(request)
            return StorageAccessUrl(url = signed.url().toURI(), expiresAt = signed.expiration())
        } catch (exception: SdkClientException) {
            throw clientFailure("createDownloadUrl", exception)
        }
    }

    override fun head(key: String): ObjectMetadata {
        require(key.isNotBlank()) { "객체 키는 비어 있거나 공백으로만 구성될 수 없습니다." }
        val request =
            HeadObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .build()
        try {
            val response = client.headObject(request)
            return ObjectMetadata(
                contentLength = response.contentLength(),
                contentType = response.contentType(),
                etag = response.eTag(),
            )
        } catch (exception: S3Exception) {
            throw storageFailure("head", exception)
        } catch (exception: SdkClientException) {
            throw clientFailure("head", exception)
        }
    }

    override fun delete(key: String) {
        require(key.isNotBlank()) { "객체 키는 비어 있거나 공백으로만 구성될 수 없습니다." }
        val request =
            DeleteObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .build()
        try {
            client.deleteObject(request)
        } catch (exception: S3Exception) {
            throw storageFailure("delete", exception)
        } catch (exception: SdkClientException) {
            throw clientFailure("delete", exception)
        }
    }

    private fun validateAccess(
        key: String,
        expiresIn: Duration,
    ) {
        require(key.isNotBlank()) { "객체 키는 비어 있거나 공백으로만 구성될 수 없습니다." }
        require(expiresIn >= Duration.ofSeconds(1) && expiresIn <= Duration.ofDays(7)) {
            "Presigned URL의 유효 기간은 1초 이상 7일 이하여야 합니다."
        }
    }

    private fun storageFailure(
        operation: String,
        exception: S3Exception,
    ): ObjectStorageException {
        val serviceCode = exception.awsErrorDetails()?.errorCode()
        val reason =
            when {
                serviceCode in
                    setOf(
                        "NoSuchBucket",
                        "InvalidAccessKeyId",
                        "SignatureDoesNotMatch",
                        "AuthorizationHeaderMalformed",
                        "NotImplemented",
                    )
                -> {
                    ObjectStorageFailureReason.CONFIGURATION
                }

                exception.statusCode() in setOf(401, 403) -> {
                    ObjectStorageFailureReason.CONFIGURATION
                }

                exception.statusCode() == 404 -> {
                    ObjectStorageFailureReason.NOT_FOUND
                }

                exception.statusCode() in setOf(408, 429, 500, 502, 503, 504) ||
                    serviceCode in setOf("SlowDown", "RequestTimeout", "ServiceUnavailable") -> {
                    ObjectStorageFailureReason.UNAVAILABLE
                }

                else -> {
                    ObjectStorageFailureReason.UNKNOWN
                }
            }
        return ObjectStorageException(
            reason = reason,
            operation = operation,
            statusCode = exception.statusCode(),
            serviceCode = serviceCode,
            requestId = exception.requestId(),
            cause = exception,
        )
    }

    private fun clientFailure(
        operation: String,
        exception: SdkClientException,
    ): ObjectStorageException {
        val connectionFailure =
            generateSequence<Throwable>(exception) { it.cause }.any {
                it is SocketException || it is SocketTimeoutException || it is UnknownHostException || it is EOFException ||
                    it is ApiCallTimeoutException || it is ApiCallAttemptTimeoutException
            }
        return ObjectStorageException(
            reason = if (connectionFailure) ObjectStorageFailureReason.UNAVAILABLE else ObjectStorageFailureReason.UNKNOWN,
            operation = operation,
            statusCode = null,
            serviceCode = null,
            requestId = null,
            cause = exception,
        )
    }
}
