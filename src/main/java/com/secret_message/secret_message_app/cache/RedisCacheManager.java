package com.secret_message.secret_message_app.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class RedisCacheManager {

    private final StringRedisTemplate redisTemplate;

    public RedisCacheManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Value("${app.auto-delete-days}")
    private long messageExpiryTime;

    @Value("${app.max-tries}")
    private int maxTries;

    private String buildMessageKey(String messageId) {
        return "messages:" + messageId;
    }

    private String buildAttemptKey(String messageId) {
        return "attempts:" + messageId;
    }

    public void storeEncryptedMessage(String messageId, String encryptedMessage) {
        redisTemplate.opsForValue().set(buildMessageKey(messageId), encryptedMessage, messageExpiryTime, TimeUnit.DAYS);
    }

    public String getEncryptedMessageById(String messageId) {
        return redisTemplate.opsForValue().get(buildMessageKey(messageId));
    }

    public void deleteEncryptedMessage(String messageId) {
        redisTemplate.delete(buildMessageKey(messageId));
    }

    /**
     * Race-closing primitive for the reveal success path.
     * Returns true only for the caller that actually performed the delete.
     */
    public boolean deleteIfPresent(String messageId) {
        Boolean deleted = redisTemplate.delete(buildMessageKey(messageId));
        return Boolean.TRUE.equals(deleted);
    }

    public boolean incrementAndCheckAttempt(String messageId) {
        String attemptKey = buildAttemptKey(messageId);
        Long attempts = redisTemplate.opsForValue().increment(attemptKey);
        if (attempts != null && attempts == 1) {
            redisTemplate.expire(attemptKey, messageExpiryTime, TimeUnit.DAYS);
        }
        if (attempts != null && attempts > maxTries) {
            deleteEncryptedMessage(messageId);
            return true;
        }
        return false;
    }

    public void resetAttempt(String messageId) {
        redisTemplate.delete(buildAttemptKey(messageId));
    }

    public Long getAttemptKeyTtl(String messageId) {
        return redisTemplate.getExpire(buildAttemptKey(messageId), java.util.concurrent.TimeUnit.SECONDS);
    }
}
