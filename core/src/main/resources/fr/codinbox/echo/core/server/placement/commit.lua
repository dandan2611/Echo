-- echo-placement-commit-v2: no application-owned lock and no non-idempotent retry.
-- KEYS: revision, reservation hash, registry, receipt, then observed/written buckets.
-- ARGV[1] describes the plan; other arguments preserve the client's binary codec.
local plan = cjson.decode(ARGV[1])
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if now >= plan.deadline then return 'EXPIRED' end
local receipt = redis.call('GET', KEYS[4])
if receipt then return receipt end
if (redis.call('GET', KEYS[1]) or '') ~= plan.revision then return 'CONFLICT' end
if redis.call('HLEN', KEYS[3]) ~= #plan.registry then return 'CONFLICT' end
for _, entry in ipairs(plan.registry) do
    if redis.call('HGET', KEYS[3], ARGV[entry[1]]) ~= ARGV[entry[2]] then return 'CONFLICT' end
end
for _, entry in ipairs(plan.reads) do
    local value = redis.call('GET', KEYS[entry.key])
    if entry.present then
        if value ~= ARGV[entry.value] then return 'CONFLICT' end
    elseif value then
        return 'CONFLICT'
    end
end
-- All checks precede mutation. A Redis script runtime error is NOT a rollback.
local kind = redis.call('TYPE', KEYS[2]).ok
if kind ~= 'none' and kind ~= 'hash' then return redis.error_reply('Invalid placement reservation type') end
for _, entry in ipairs(plan.writes) do
    local bucketKind = redis.call('TYPE', KEYS[entry.key]).ok
    if bucketKind ~= 'none' and bucketKind ~= 'string' then return redis.error_reply('Invalid placement bucket type') end
end
if plan.changed then
    for _, entry in ipairs(plan.registrations) do redis.call('HSET', KEYS[3], ARGV[entry[1]], ARGV[entry[2]]) end
    for _, key in ipairs(plan.removed) do redis.call('HDEL', KEYS[2], key) end
    for _, entry in ipairs(plan.reservations) do redis.call('HSET', KEYS[2], entry[1], entry[2]) end
    for _, entry in ipairs(plan.writes) do redis.call('SET', KEYS[entry.key], ARGV[entry.value]) end
    redis.call('SET', KEYS[1], plan.attempt)
end
-- The deadline forbids replay after this receipt can expire.
redis.call('SET', KEYS[4], plan.attempt, 'PX', 60000)
return plan.attempt
