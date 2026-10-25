package com.sharefo.aiworkbench.sqllab.exec;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SqlErrorTranslator {

	private static final Pattern COLUMN_NOT_FOUND =
			Pattern.compile("Column \"([^\"]+)\" not found", Pattern.CASE_INSENSITIVE);

	private static final Pattern TABLE_NOT_FOUND =
			Pattern.compile("Table \"([^\"]+)\" not found", Pattern.CASE_INSENSITIVE);

	private SqlErrorTranslator() {
	}

	public static String translate(String raw) {
		if (raw == null || raw.isBlank()) {
			return "执行失败，但没有拿到具体的错误信息";
		}
		String msg = raw.replaceAll("\\s*\\[\\d+-\\d+\\]$", "").trim();
		Matcher column = COLUMN_NOT_FOUND.matcher(msg);
		if (column.find()) {
			return "找不到字段 “" + column.group(1) + "”。可能是字段拼写不对，或者这张表里根本没有这个字段，"
					+ "可以在左侧表结构里确认一下。";
		}
		Matcher table = TABLE_NOT_FOUND.matcher(msg);
		if (table.find()) {
			return "找不到表 “" + table.group(1) + "”。确认下表名，或者刷新左侧表结构看看空间里现在有哪些表。";
		}
		String lower = msg.toLowerCase(Locale.ROOT);
		if (lower.contains("syntax error")) {
			return "SQL 语法有问题：" + msg + "（常见原因：逗号多了或少了、括号没配对、关键字拼错）";
		}
		if (lower.contains("timeout")) {
			return "查询超时了（超过限制时间）。可以加 WHERE 缩小范围，或者加 LIMIT 减少返回行数。";
		}
		if (lower.contains("division by zero")) {
			return "出现了除以 0 的情况，做除法前记得用 NULLIF(分母, 0) 保护一下。";
		}
		if (lower.contains("data conversion error")) {
			return "数据类型转换失败：" + msg + "（常见原因：把文字塞进了数字字段，或日期格式不对）";
		}
		if (lower.contains("unique index") || lower.contains("primary key")) {
			return "主键冲突：这条数据的主键值已经存在了。";
		}
		if (lower.contains("ambiguous column name")) {
			return "有字段在多张表里都叫这个名字，需要在字段前加上表名前缀，比如 o.id、u.id。";
		}
		return msg;
	}
}
