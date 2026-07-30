package com.nivas;

import com.nivas.config.NivasProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(NivasProperties.class)
@EnableJpaAuditing
@EnableCaching
@EnableScheduling
public class NivasApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(NivasApiApplication.class, args);
	}

}
