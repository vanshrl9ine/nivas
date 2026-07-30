package com.nivas.config;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

@Configuration
public class RedisConfig {

    /**
     * JSON rather than JDK serialisation, for two reasons that matter in practice:
     * cached entries stay readable from {@code redis-cli} while debugging, and a
     * class whose {@code serialVersionUID} shifts between deploys does not poison
     * the whole cache.
     *
     * <p>Built on its own mapper rather than the application's. The shared mapper
     * is tuned for the HTTP wire format — omitting nulls, ISO dates — whereas a
     * cache value has to round-trip back into the exact object it came from.
     */
    @Bean
    GenericJacksonJsonRedisSerializer cacheValueSerializer() {
        ObjectMapper mapper = JsonMapper.builder()
                // Type information is required to deserialise back into the
                // original type. The validator restricts that to our own packages
                // so a tampered cache entry cannot name an arbitrary class as a
                // deserialisation gadget.
                .activateDefaultTyping(
                        BasicPolymorphicTypeValidator.builder()
                                .allowIfBaseType(Object.class)
                                .allowIfSubType("com.nivas.")
                                .allowIfSubType("java.util.")
                                .allowIfSubType("java.time.")
                                .allowIfSubType("java.math.")
                                .build(),
                        DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY)
                .build();

        return new GenericJacksonJsonRedisSerializer(mapper);
    }

    /**
     * Per-cache TTLs. A single global TTL would force the same expiry on locality
     * reference data (safe for hours) and on search results (stale within seconds).
     */
    @Bean
    RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
            GenericJacksonJsonRedisSerializer valueSerializer,
            NivasProperties properties) {

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer))
                // Caching a null would let a 404 pin itself in the cache for the
                // whole TTL, which is exactly wrong for a listing about to be published.
                .disableCachingNullValues()
                .prefixCacheNameWith("nivas:cache:");

        NivasProperties.Cache ttls = properties.cache();
        Map<String, RedisCacheConfiguration> perCache = Map.of(
                CacheNames.PROPERTY_DETAIL, base.entryTtl(ttls.propertyDetailTtl()),
                CacheNames.SEARCH_RESULTS, base.entryTtl(ttls.searchResultsTtl()),
                CacheNames.LOCALITIES, base.entryTtl(ttls.localityTtl()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base.entryTtl(ttls.searchResultsTtl()))
                .withInitialCacheConfigurations(perCache)
                // Defers eviction until after commit. Evicting mid-transaction
                // opens a window where a concurrent read repopulates the cache
                // from the pre-commit state.
                .transactionAware()
                .build();
    }

    /**
     * The sliding-window rate limit, as a single script.
     *
     * <p>Lua matters here: check-then-increment across two round trips is a race,
     * and under concurrency it lets callers exceed the limit. A script executes
     * atomically on the server, so the decision and the record of it cannot be
     * interleaved with another request.
     */
    @Bean
    RedisScript<List> rateLimitScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/sliding-window-rate-limit.lua"));
        script.setResultType(List.class);
        return script;
    }
}
