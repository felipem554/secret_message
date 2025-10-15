package com.secret_message.secret_message_app.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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

    // Store and Set to delete in days
    public void storeEncryptedMessage(String messageId, String encryptedMessage) {
        redisTemplate.opsForValue().set(buildMessageKey(messageId), encryptedMessage, messageExpiryTime, TimeUnit.DAYS);
    }

    public String getEncryptedMessageById(String messageId) {
        return redisTemplate.opsForValue().get(messageId);
    }

    public void deleteEncryptedMessage(String messageId) {
        redisTemplate.delete(buildMessageKey(messageId));
    }

    public boolean incrementAndCheckAttempt(String messageId) {
        Long attempts = redisTemplate.opsForValue().increment(buildAttemptKey(messageId));
        if (attempts != null && attempts > maxTries) {
            // Delete message and AES key if max attempts exceeded
            deleteEncryptedMessage(messageId);
            return true;
        }
        return false;
    }

    public void resetAttempt(String messageId) {
        redisTemplate.delete(buildAttemptKey(messageId));
    }
}
