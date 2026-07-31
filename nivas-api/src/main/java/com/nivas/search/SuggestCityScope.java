package com.nivas.search;

import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.nivas.config.CacheNames;

/**
 * Caches the set of cities the type-ahead scopes over.
 *
 * <p>A whole bean for one cached list looks like overkill, and is not.
 * {@code @Cacheable} is applied by a proxy, so annotating the method on
 * {@link PropertyDiscoveryService} and calling it from that same class would
 * bypass the cache entirely — the aggregation would then run on every keystroke,
 * which is the one place it must not.
 */
@Component
class SuggestCityScope {

    private final PropertyDiscoveryService discoveryService;

    /**
     * Lazy because the dependency is circular: discovery needs the cached scope,
     * and the scope needs discovery to compute it. A lazy proxy breaks the cycle
     * without either class knowing about it.
     */
    SuggestCityScope(@Lazy PropertyDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @Cacheable(cacheNames = CacheNames.LOCALITIES, key = "'suggest-city-scope'")
    public List<String> lowercasedCityNames() {
        return discoveryService.loadCityNames();
    }
}
