package com.runmate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class RunmateApplication {

	public static void main(String[] args) {
		SpringApplication.run(RunmateApplication.class, args);
	}

}
