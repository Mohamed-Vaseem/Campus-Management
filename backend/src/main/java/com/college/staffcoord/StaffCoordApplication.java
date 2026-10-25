package com.college.staffcoord;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StaffCoordApplication {
    public static void main(String[] args) { SpringApplication.run(StaffCoordApplication.class, args); }
}
