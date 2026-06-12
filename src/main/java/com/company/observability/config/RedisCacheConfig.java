package com.company.observability.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

@Configuration
public class RedisCacheConfig {

    /**
     * Optimized Lettuce connection factory with connection pooling
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        // Socket options for better performance
        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .keepAlive(true)
                .build();

        // Client options
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .autoReconnect(true)
                .build();

        // Lettuce client configuration
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .commandTimeout(Duration.ofSeconds(2))
                .build();

        // Redis standalone configuration
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration();
        serverConfig.setHostName(System.getenv().getOrDefault("REDIS_HOST", "localhost"));
        serverConfig.setPort(Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));

        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) {
            serverConfig.setPassword(password);
        }

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }
}