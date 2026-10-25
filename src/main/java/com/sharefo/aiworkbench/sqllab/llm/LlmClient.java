package com.sharefo.aiworkbench.sqllab.llm;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.sharefo.aiworkbench.sqllab.config.LlmProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容协议的 LLM 客户端（DeepSeek / OpenAI / 通义 / 本地 vLLM 通用）。
 * 只依赖 JDK 自带的 HttpClient，流式用 SSE 手工解析。
 */
@Component
public class LlmClient {

	private final LlmProperties properties;

	private final ObjectMapper mapper;

	private final HttpClient http;

	public LlmClient(LlmProperties properties, ObjectMapper mapper) {
		this.properties = properties;
		this.mapper = mapper;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
	}

	public void stream(List<Map<String, Object>> messages, Consumer<String> onDelta) {
		stream(messages, properties.getTemperature(), onDelta);
	}

	public void stream(List<Map<String, Object>> messages, double temperature, Consumer<String> onDelta) {
		Map<String, Object> body = baseBody(messages, temperature);
		body.put("stream", true);
		HttpRequest request = request(body).timeout(Duration.ofMillis(properties.getTimeoutMs())).build();
		try {
			HttpResponse<InputStream> response =
					http.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				throw new LlmException(friendly("HTTP " + response.statusCode(), readAll(response.body())));
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(),
					StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					String trimmed = line.trim();
					if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
						continue;
					}
					String payload = trimmed.substring(5).trim();
					if ("[DONE]".equals(payload)) {
						break;
					}
					JsonNode node = mapper.readTree(payload);
					JsonNode delta = node.path("choices").path(0).path("delta").path("content");
					if (!delta.isMissingNode() && !delta.isNull()) {
						onDelta.accept(delta.asString());
						continue;
					}
					JsonNode msg = node.path("choices").path(0).path("message").path("content");
					if (!msg.isMissingNode() && !msg.isNull()) {
						onDelta.accept(msg.asString());
					}
				}
			}
		}
		catch (LlmException e) {
			throw e;
		}
		catch (Exception e) {
			throw new LlmException("调用模型失败：" + e.getMessage(), e);
		}
	}

	/**
	 * 一次性取回完整文本。
	 * 实测部分厂商（如智谱）在长输出 + 非流式时容易挂住不返回，而流式始终是稳的，
	 * 所以这里统一走流式再拼接，避免依赖非流式通道。
	 */
	public String complete(List<Map<String, Object>> messages, double temperature) {
		StringBuilder sb = new StringBuilder();
		stream(messages, temperature, sb::append);
		return sb.toString();
	}

	private Map<String, Object> baseBody(List<Map<String, Object>> messages, double temperature) {
		return new java.util.LinkedHashMap<>(Map.of(
				"model", properties.getModel(),
				"messages", messages,
				"temperature", temperature));
	}

	private HttpRequest.Builder request(Map<String, Object> body) {
		String url = properties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
		return HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.header("Authorization", "Bearer " + properties.getApiKey())
				.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),
						StandardCharsets.UTF_8));
	}

	/** 把厂商返回的英文错误翻成能看懂的中文提示 */
	public static String friendly(String statusInfo, String body) {
		String lower = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
		if (body != null && (lower.contains("insufficient balance") || lower.contains("insufficient_balance"))) {
			return "模型账户余额不足，请在对应厂商后台充值后再使用 AI 功能（手写 SQL、造数、闯关练习都不受影响）";
		}
		if (body != null && (lower.contains("invalid api key") || lower.contains("authentication fails")
				|| lower.contains("incorrect api key"))) {
			return "API Key 无效或已失效，请检查 application-local.yml 里的 llm.api-key";
		}
		if (body != null && lower.contains("model") && lower.contains("does not exist")) {
			return "模型名不存在，请检查配置 llm.model 是否写错";
		}
		if (body != null && lower.contains("rate limit")) {
			return "触发了厂商限流，稍等几秒再试";
		}
		return "模型返回 " + statusInfo + "：" + body;
	}

	private static String readAll(InputStream in) {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = r.readLine()) != null && sb.length() < 2000) {
				sb.append(line);
			}
			return sb.toString();
		}
		catch (Exception e) {
			return "";
		}
	}

	public static class LlmException extends RuntimeException {

		public LlmException(String message) {
			super(message);
		}

		public LlmException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
