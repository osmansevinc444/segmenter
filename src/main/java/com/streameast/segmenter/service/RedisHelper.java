package com.streameast.segmenter.service;

import com.streameast.segmenter.model.StreamContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    public List<StreamContext> getScheduledContexts(LocalDateTime dateTime) {
        // Retrieve all keys
        Set<String> keys = redisTemplate.keys("*"); // Use a wildcard to get all keys
        List<StreamContext> results = new ArrayList<>();

        if (keys != null) {
            for (String key : keys) {
                StreamContext context = redisTemplate.opsForValue().get(key);
                if (context != null
                        && context.getPId() == -1
                        && context.isProccessing() == false
                        && context.isActive() == true) {

                    if( dateTime==null || (dateTime != null && dateTime.isAfter(context.getStartTime()))){
                        results.add(context);
                    }
                }
            }
        }
        return results;
    }
}

