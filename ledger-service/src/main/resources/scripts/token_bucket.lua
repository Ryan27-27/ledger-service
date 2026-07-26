-- Atomic token bucket. Runs entirely inside Redis (single-threaded), so
-- the read-refill-check-decrement sequence can't race across instances --
-- this is the whole point of moving the limiter out of app memory.
--
-- KEYS[1]  = bucket key, e.g. "ratelimit:debit:<accountId>"
-- ARGV[1]  = capacity (max tokens / burst size)
-- ARGV[2]  = refill rate, tokens per second
-- ARGV[3]  = now, unix time in seconds (float)
-- ARGV[4]  = requested tokens for this call (normally 1)
--
-- returns 1 if allowed, 0 if rate-limited

local bucket_key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

local bucket = redis.call('HMGET', bucket_key, 'tokens', 'timestamp')
local tokens = tonumber(bucket[1])
local last_refill = tonumber(bucket[2])

if tokens == nil then
    tokens = capacity
    last_refill = now
end

local elapsed = math.max(0, now - last_refill)
local refilled = math.min(capacity, tokens + (elapsed * refill_rate))

local allowed = 0
if refilled >= requested then
    allowed = 1
    refilled = refilled - requested
end

redis.call('HMSET', bucket_key, 'tokens', refilled, 'timestamp', now)
-- bucket is idle-expired after 1 hour so we don't accumulate keys forever
redis.call('EXPIRE', bucket_key, 3600)

return allowed
