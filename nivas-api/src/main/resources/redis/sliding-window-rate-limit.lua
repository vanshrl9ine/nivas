-- Sliding-window rate limiter backed by a sorted set.
--
-- Each request is one member scored by its timestamp. Anything older than the
-- window is dropped before counting, so the limit slides continuously instead of
-- resetting on a fixed boundary. Fixed windows allow a burst of 2x the limit
-- across a boundary; this does not.
--
-- KEYS[1] : the bucket key (one per subject per endpoint)
-- ARGV[1] : now, in milliseconds
-- ARGV[2] : window length, in milliseconds
-- ARGV[3] : permitted requests per window
-- ARGV[4] : a unique member id for this request
--
-- Returns { allowed (1|0), remaining, retryAfterMillis }

local key       = KEYS[1]
local now       = tonumber(ARGV[1])
local window    = tonumber(ARGV[2])
local limit     = tonumber(ARGV[3])
local member    = ARGV[4]

-- Evict everything that has fallen out of the window.
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

local used = redis.call('ZCARD', key)

if used < limit then
    redis.call('ZADD', key, now, member)
    -- Refreshed on every accepted request, so an idle bucket expires on its own
    -- and we never accumulate keys for one-off callers.
    redis.call('PEXPIRE', key, window)
    return { 1, limit - used - 1, 0 }
end

-- Rejected. The caller may retry once the oldest entry leaves the window.
local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
local retry_after = window
if oldest[2] then
    retry_after = (tonumber(oldest[2]) + window) - now
    if retry_after < 0 then
        retry_after = 0
    end
end

return { 0, 0, retry_after }
