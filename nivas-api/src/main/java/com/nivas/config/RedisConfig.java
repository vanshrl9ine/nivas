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
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

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
    public GenericJacksonJsonRedisSerializer cacheValueSerializer() {
        /*
         * Restricts polymorphic deserialisation to types we own, so a tampered
         * cache entry cannot name an arbitrary class as a gadget. Object is
         * allowed as a base type because cached values are read back as Object.
         */
        PolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .allowIfSubType("com.nivas.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.math.")
                .allowIfSubType("java.lang.")
                .build();

        /*
         * enableDefaultTyping on the builder, rather than activateDefaultTyping
         * on a hand-built mapper.
         *
         * Configuring it by hand with DefaultTyping.NON_FINAL looks right and is
         * quietly broken here: every DTO in this application is a record, records
         * are final, so no type id was written for them — and nothing could be
         * read back. The failure only appears on a cache *hit*, so the first
         * request populates happily and the second one 500s.
         *
         * Spring's builder selects a typing strategy that round-trips its own
         * output, which is the only property that actually matters for a cache.
         * CacheSerializerRoundTripTest pins it.
         */
        return GenericJacksonJsonRedisSerializer.builder()
                .customize(mapper -> mapper.activateDefaultTyping(
                        validator,
                        // NON_FINAL_AND_RECORDS, not NON_FINAL. Records are final, so
                        // NON_FINAL writes no type id for them and a cached List of
                        // records cannot be read back. Jackson 3 added this mode for
                        // precisely this problem.
                        DefaultTyping.NON_FINAL_AND_RECORDS,
                        JsonTypeInfo.As.PROPERTY))
                // Writes with Object as the declared root type.
                //
                // Without this the writer and the reader disagree, which is subtle
                // enough to be worth spelling out. Jackson decides whether to emit a
                // type id from the *declared* type; the default writer declares the
                // runtime class, and List.of(...) is ImmutableCollections$ListN —
                // final, so no type id. The reader always targets Object, which
                // requires one. Observed directly:
                //
                //   wrote: ["Bengaluru","Mumbai"]
                //   read : Could not resolve type id 'Bengaluru'
                //
                // Declaring Object on the way out makes both sides agree. Scalars
                // hid this in casual testing because Jackson round-trips those
                // natively without a type id.
                .writer((mapper, value) -> mapper.writerFor(Object.class).writeValueAsBytes(value))
                .build();
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
