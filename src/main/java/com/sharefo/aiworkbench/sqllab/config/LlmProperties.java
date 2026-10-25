package com.sharefo.aiworkbench.sqllab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("llm")
public class LlmProperties {

	private boolean enabled = false;

	private String baseUrl = "https://api.deepseek.com/v1";

	private String apiKey = "";

	private String model = "deepseek-chat";

	private double temperature = 0.1;

	private long timeoutMs = 120000L;

	private int maxContextTables = 24;

	public boolean isAvailable() {
		return enabled && apiKey != null && !apiKey.isBlank();
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public double getTemperature() {
		return temperature;
	}

	public void setTemperature(double temperature) {
		this.temperature = temperature;
	}

	public long getTimeoutMs() {
		return timeoutMs;
	}

	public void setTimeoutMs(long timeoutMs) {
		this.timeoutMs = timeoutMs;
	}

	public int getMaxContextTables() {
		return maxContextTables;
	}

	public void setMaxContextTables(int maxContextTables) {
		this.maxContextTables = maxContextTables;
	}
}
