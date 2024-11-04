package com.streameast.segmenter;

import com.streameast.segmenter.config.AppSettings;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SegmenterApp {

    public static void main(String[] args) {
        SpringApplication.run(SegmenterApp.class, args);
    }

    @Bean
    @ConfigurationProperties()
    public AppSettings appSettings () {
        return new AppSettings();
    }
}
