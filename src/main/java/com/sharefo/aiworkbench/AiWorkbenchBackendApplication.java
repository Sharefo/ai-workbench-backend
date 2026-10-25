package com.sharefo.aiworkbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("com.sharefo.aiworkbench.sqllab.config")
public class AiWorkbenchBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiWorkbenchBackendApplication.class, args);
	}
}
