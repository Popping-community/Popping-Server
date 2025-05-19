package com.example.popping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class PoppingApplication {

	public static void main(String[] args) {
		SpringApplication.run(PoppingApplication.class, args);
	}

}
