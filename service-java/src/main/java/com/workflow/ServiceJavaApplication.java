package com.workflow;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.workflow.mapper")
@SpringBootApplication
public class ServiceJavaApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServiceJavaApplication.class, args);
    }
}