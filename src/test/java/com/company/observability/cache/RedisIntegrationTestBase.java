package com.company.observability.cache;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Abstract base class for Redis integration tests using Testcontainers.
 *
 * <p>Mirrors the pattern established by {@code PostgresJdbcIntegrationTestBase}.
 * The container is declared {@code static} so it is shared across all test
 * methods in a subclass, avoiding per-test container restart overhead.
 *
 * <p>Subclasses must add their own Spring context slice annotation
 * (e.g. {@code @SpringBootTest}) and an {@code @Autowired RedisTemplate} field.
 * A typical subclass skeleton:
 *
 * <pre>{@code
 * @SpringBootTest(classes = {RedisConfig.class, MyCache.class})
 * class MyCacheIntegrationTest extends RedisIntegrationTestBase {
 *     @Autowired RedisTemplate<String, Object> redisTemplate;
 *
 *     @BeforeEach void flush() { redisTemplate.getConnectionFactory()
 *         .getConnection().serverCommands().flushAll(); }
 * }
 * }</pre>
 *
 * <p>No additional Maven dependency is required: a {@code GenericContainer} with
 * the official {@code redis:7-alpine} image suffices for Testcontainers 1.19+.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class RedisIntegrationTestBase {

    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }
}
