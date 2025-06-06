package com.example.popping.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.example.popping.service.ImageService;

@Slf4j
@Component
@RequiredArgsConstructor
public class TempImageCleanupScheduler {

    private final ImageService imageService;

    @Scheduled(cron = "0 0 0 * * *")
    public void runCleanupTask() {
        imageService.cleanUpUnusedTempImages();
        log.info("Temporary image cleanup task executed successfully.");
    }
}
