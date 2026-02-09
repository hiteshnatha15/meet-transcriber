package com.transcriber;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class MeetTranscriberApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeetTranscriberApplication.class, args);
    }
}
