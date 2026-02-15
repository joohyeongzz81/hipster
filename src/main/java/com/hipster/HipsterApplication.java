package com.hipster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy
@SpringBootApplication
public class HipsterApplication {

    public static void main(String[] args) {
        SpringApplication.run(HipsterApplication.class, args);
    }

}
