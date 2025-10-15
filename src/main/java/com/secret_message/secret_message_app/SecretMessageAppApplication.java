package com.secret_message.secret_message_app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.secret_message.secret_message_app")
public class SecretMessageAppApplication {

	public static void main(String[] args) {
		SpringApplication.run(SecretMessageAppApplication.class, args);
	}

}
