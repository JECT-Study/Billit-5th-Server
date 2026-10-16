package com.ject.external.storage.s3

import com.ject.external.storage.ObjectStorage
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.time.Duration

@Configuration
@ConditionalOnProperty(prefix = "app.storage", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(S3StorageProperties::class)
class S3StorageConfig {
    @Bean(destroyMethod = "close")
    fun s3Client(properties: S3StorageProperties): S3Client =
        S3Client
            .builder()
            .endpointOverride(properties.endpoint)
            .region(Region.of(properties.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey, properties.secretKey)))
            .forcePathStyle(true)
            .httpClientBuilder(
                UrlConnectionHttpClient
                    .builder()
                    .connectionTimeout(Duration.ofSeconds(2))
                    .socketTimeout(Duration.ofSeconds(5)),
            ).overrideConfiguration(
                ClientOverrideConfiguration
                    .builder()
                    .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                    .build(),
            ).build()

    @Bean(destroyMethod = "close")
    fun s3Presigner(properties: S3StorageProperties): S3Presigner =
        S3Presigner
            .builder()
            .endpointOverride(properties.endpoint)
            .region(Region.of(properties.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey, properties.secretKey)))
            .serviceConfiguration(
                S3Configuration
                    .builder()
                    .pathStyleAccessEnabled(true)
                    .build(),
            ).build()

    @Bean
    fun objectStorage(
        client: S3Client,
        presigner: S3Presigner,
        properties: S3StorageProperties,
    ): ObjectStorage = S3ObjectStorage(client, presigner, properties.bucket)
}
