package com.sharefo.aiworkbench.sqllab.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LlmJsonExtractor {

	private static final Pattern FENCE = Pattern.compile("```(?:sql|SQL|mysql)?\\s*([\\s\\S]*?)```");

	private static final Pattern JSON_FENCE = Pattern.compile("```(?:json|JSON)?\\s*([\\s\\S]*?)```");

	private LlmJsonExtractor() {
	}

	/** 从大模型输出中提取 SQL：优先 ```sql 围栏，其次 ``` 围栏，其次整段文本 */
	public static String extractSql(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		Matcher m = FENCE.matcher(text);
		if (m.find()) {
			String inner = m.group(1).trim();
			if (looksLikeSql(inner)) {
				return inner;
			}
		}
		Matcher any = Pattern.compile("```[\\s\\S]*?```").matcher(text);
		if (any.find() && looksLikeSql(any.group(0))) {
			return any.group(0).replace("```", "").trim();
		}
		String trimmed = text.trim();
		return looksLikeSql(trimmed) ? trimmed : "";
	}

	/** 去掉 SQL 代码块后剩下的说明文字 */
	public static String extractExplanation(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		String withoutFence = FENCE.matcher(text).replaceAll("").trim();
		if (!withoutFence.isBlank()) {
			return withoutFence;
		}
		return text.replace("```", "").trim();
	}

	public static String extractJson(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		Matcher m = JSON_FENCE.matcher(text);
		if (m.find()) {
			return m.group(1).trim();
		}
		int start = text.indexOf('{');
		int end = text.lastIndexOf('}');
		if (start >= 0 && end > start) {
			return text.substring(start, end + 1);
		}
		return "";
	}

	private static boolean looksLikeSql(String s) {
		String upper = s.toUpperCase();
		return upper.contains("SELECT") || upper.contains("INSERT") || upper.contains("CREATE")
				|| upper.contains("UPDATE") || upper.contains("DELETE") || upper.contains("WITH");
	}
}
