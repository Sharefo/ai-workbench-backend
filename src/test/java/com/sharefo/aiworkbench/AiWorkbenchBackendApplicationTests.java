package com.sharefo.aiworkbench;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:contexttest;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"llm.enabled=false"
})
class AiWorkbenchBackendApplicationTests {

	@Test
	void contextLoads() {
	}
}
