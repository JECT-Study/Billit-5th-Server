package com.ject.support

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer

@ActiveProfiles("test")
@SpringBootTest
abstract class IntegrationTestSupport : FunSpec() {
    init {
        extensions(SpringExtension())
    }

    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    companion object {
        private const val STORAGE_BUCKET = "billit-test"

        private val postgres = PostgreSQLContainer("postgres:17-alpine").apply { start() }

        private val redis =
            GenericContainer("redis:7.4-alpine")
                .withExposedPorts(6379)
                .apply { start() }

        private val s3 =
            GenericContainer("adobe/s3mock:5.2.3")
                .withExposedPorts(9090)
                .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", STORAGE_BUCKET)
                .waitingFor(Wait.forHttp("/favicon.ico").forPort(9090).forStatusCode(200))
                .apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            registry.add("app.storage.enabled") { true }
            registry.add("app.storage.s3.endpoint") { "http://${s3.host}:${s3.getMappedPort(9090)}" }
            registry.add("app.storage.s3.bucket") { STORAGE_BUCKET }
            registry.add("app.storage.s3.region") { "us-east-1" }
            registry.add("app.storage.s3.access-key") { "test" }
            registry.add("app.storage.s3.secret-key") { "test" }
        }
    }
}
