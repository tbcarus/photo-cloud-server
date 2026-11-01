package ru.tbcarus.photocloudserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PhotoCloudServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PhotoCloudServerApplication.class, args);
    }

}
