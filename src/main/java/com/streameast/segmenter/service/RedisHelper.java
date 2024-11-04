package com.streameast.segmenter.service;

import com.streameast.segmenter.model.StreamContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisHelper {

    private final RedisTemplate<String, StreamContext> redisTemplate;

    public RedisHelper(RedisTemplate<String, StreamContext> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void saveContext(String key, StreamContext context) {
        redisTemplate.opsForValue().set(key, context);
    }

    public StreamContext getContext(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    public void deleteContext(String key) {
        redisTemplate.delete(key);
    }
}

