package com.streameast.segmenter.service;

import com.streameast.segmenter.config.NotificationConfig;
import com.streameast.segmenter.model.StreamContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class NotificationService {
    private final NotificationConfig config;
    private final StreamSchedulerService schedulerService;
    private final RestTemplate restTemplate;
    private final RedisHelper redisHelper;
    private static final String TELEGRAM_API_URL = "https://api.telegram.org/bot%s/sendMessage";

    public NotificationService(NotificationConfig config, StreamSchedulerService schedulerService, RedisHelper redisHelper) {
        this.config = config;
        this.schedulerService = schedulerService;
        this.redisHelper = redisHelper;
        this.restTemplate = new RestTemplate();
    }

    @Scheduled(fixedRate = 30, timeUnit = TimeUnit.SECONDS)
    public void checkAndNotify() {
        if (!config.isEnabled()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        redisHelper.getScheduledStreamForNotification(now).forEach(stream -> {
            long minutesUntilStart = ChronoUnit.MINUTES.between(now, stream.getStartTime());
            if (config.getNotifyBeforeMinutes().contains(String.valueOf(minutesUntilStart))) {
                sendNotifications(stream, minutesUntilStart);
            }
        });
    }

    private void sendNotifications(StreamContext stream, long minutesUntilStart) {
        if (config.getTelegram().isEnabled()) {
            sendTelegram(stream, minutesUntilStart);
        }
    }

    private void sendTelegram(StreamContext stream, long minutesUntilStart) {
        try {
            String message = String.format(config.getTelegram().getTemplate(),
                    stream.getStreamUrl(),
                    minutesUntilStart,
                    stream.getVideoQuality());

            String botToken = config.getTelegram().getBotToken();

            for (String chatId : config.getTelegram().getChatIds()) {
                try {
                    String apiUrl = String.format(TELEGRAM_API_URL, botToken) +
                            "?chat_id=" + chatId +
                            "&text=" + message.replaceAll(" ", "%20");

                    // Send the message using GET with query parameters
                    restTemplate.getForObject(apiUrl, String.class);

                    log.info("Telegram notification sent to chat {}", chatId);
                } catch (Exception e) {
                    log.error("Failed to send Telegram notification to chat {}: {}", chatId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to send Telegram notifications", e);
        }
    }
}
