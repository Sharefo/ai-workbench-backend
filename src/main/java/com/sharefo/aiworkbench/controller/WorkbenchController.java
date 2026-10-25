package com.sharefo.aiworkbench.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class WorkbenchController {

	@GetMapping("/info")
	public Map<String, String> info() {
		return Map.of(
				"name", "ai-workbench",
				"version", "0.0.1"
		);
	}
}
