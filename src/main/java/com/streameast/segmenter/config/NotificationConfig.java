package com.streameast.segmenter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "notification")
public class NotificationConfig {
    private Telegram telegram = new Telegram();
    private List<String> notifyBeforeMinutes;
    private boolean enabled;

    @Data
    public static class Telegram {
        private boolean enabled;
        private String botToken;
        private List<String> chatIds;
        private String template;
    }

}
