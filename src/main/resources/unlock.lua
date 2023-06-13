--获取锁
--获取锁标识，判断与当前线程标识是否一致
if(redis.call('get',KEYS[1]) == ARGV[1]) then
--一致，删除锁
return redis.call('del',KEYS[1])
end
--不一致，直接返回
return 0