package com.sharefo.aiworkbench.sqllab.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.sharefo.aiworkbench.sqllab.config.SqlLabProperties;

/**
 * SQL 执行护栏：负责语句拆分、方言便利命令翻译、读写模式判定、危险语句拦截。
 */
@Component
public class SqlSafetyGuard {

	private static final Set<String> DDL_VERBS = Set.of("CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "COMMENT");

	private static final Set<String> DML_VERBS = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE", "UPSERT");

	private static final Set<String> SELECT_VERBS = Set.of("SELECT", "VALUES", "EXPLAIN", "TABLE", "WITH");

	private static final Set<String> BLOCKED_VERBS =
			Set.of("SET", "CALL", "GRANT", "REVOKE", "SHUTDOWN", "RUNSCRIPT", "SCRIPT", "BACKUP", "BEGIN", "COMMIT",
					"ROLLBACK", "START", "USE", "EXECUTE", "PREPARE", "DECLARE");

	/** 命中即拒绝（含子串匹配） */
	private static final List<String> BLOCKED_TOKENS = List.of(
			"shutdown", "runscript", "script to", "script  to", "link_schema", "create alias", "create trigger",
			"drop all objects", 			"create user", "alter user", "set password", "backup to", "export to");

	private final SqlLabProperties properties;

	public SqlSafetyGuard(SqlLabProperties properties) {
		this.properties = properties;
	}

	public enum Kind {
		SELECT, DML, DDL, BLOCKED
	}

	public static class Outcome {

		private final Kind kind;

		private final String sql;

		private final boolean confirmRequired;

		private final String confirmMessage;

		private final String blockReason;

		Outcome(Kind kind, String sql, boolean confirmRequired, String confirmMessage, String blockReason) {
			this.kind = kind;
			this.sql = sql;
			this.confirmRequired = confirmRequired;
			this.confirmMessage = confirmMessage;
			this.blockReason = blockReason;
		}

		static Outcome pass(Kind kind, String sql) {
			return new Outcome(kind, sql, false, null, null);
		}

		static Outcome confirm(Kind kind, String sql, String message) {
			return new Outcome(kind, sql, true, message, null);
		}

		static Outcome blocked(String reason) {
			return new Outcome(Kind.BLOCKED, null, false, null, reason);
		}

		public Kind getKind() {
			return kind;
		}

		public String getSql() {
			return sql;
		}

		public boolean isConfirmRequired() {
			return confirmRequired;
		}

		public String getConfirmMessage() {
			return confirmMessage;
		}

		public String getBlockReason() {
			return blockReason;
		}
	}

	/** 去掉注释后按分号拆分语句 */
	public List<String> split(String script) {
		List<String> result = new ArrayList<>();
		if (script == null || script.isBlank()) {
			return result;
		}
		StringBuilder current = new StringBuilder();
		int len = script.length();
		int i = 0;
		while (i < len) {
			char c = script.charAt(i);
			if (c == '\'' || c == '"' || c == '`') {
				char quote = c;
				current.append(c);
				i++;
				while (i < len) {
					char d = script.charAt(i);
					current.append(d);
					i++;
					if (d == quote) {
						if (i < len && script.charAt(i) == quote) {
							current.append(script.charAt(i));
							i++;
							continue;
						}
						break;
					}
				}
				continue;
			}
			if (c == '-' && i + 1 < len && script.charAt(i + 1) == '-') {
				while (i < len && script.charAt(i) != '\n') {
					i++;
				}
				continue;
			}
			if (c == '/' && i + 1 < len && script.charAt(i + 1) == '*') {
				i += 2;
				while (i + 1 < len && !(script.charAt(i) == '*' && script.charAt(i + 1) == '/')) {
					i++;
				}
				i = Math.min(i + 2, len);
				continue;
			}
			if (c == ';') {
				result.add(current.toString());
				current.setLength(0);
				i++;
				continue;
			}
			current.append(c);
			i++;
		}
		result.add(current.toString());
		return result;
	}

	/** 去掉行注释与块注释（保留字符串字面量） */
	public String stripComments(String sql) {
		StringBuilder sb = new StringBuilder();
		int len = sql.length();
		int i = 0;
		while (i < len) {
			char c = sql.charAt(i);
			if (c == '\'' || c == '"' || c == '`') {
				char quote = c;
				sb.append(c);
				i++;
				while (i < len) {
					char d = sql.charAt(i);
					sb.append(d);
					i++;
					if (d == quote) {
						if (i < len && sql.charAt(i) == quote) {
							sb.append(sql.charAt(i));
							i++;
							continue;
						}
						break;
					}
				}
				continue;
			}
			if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
				while (i < len && sql.charAt(i) != '\n') {
					i++;
				}
				continue;
			}
			if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
				i += 2;
				while (i + 1 < len && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
					i++;
				}
				i = Math.min(i + 2, len);
				continue;
			}
			sb.append(c);
			i++;
		}
		return sb.toString();
	}

	public Outcome inspect(String statement, boolean admin, String schema) {
		String cleaned = stripComments(statement).trim();
		if (cleaned.isEmpty()) {
			return Outcome.blocked(null);
		}
		String body = cleaned.endsWith(";") ? cleaned.substring(0, cleaned.length() - 1).trim() : cleaned;

		Optional<String> translated = translate(body, schema);
		if (translated.isPresent()) {
			return Outcome.pass(Kind.SELECT, translated.get());
		}

		String lower = body.toLowerCase(Locale.ROOT);
		for (String bad : BLOCKED_TOKENS) {
			if (lower.contains(bad)) {
				return Outcome.blocked("出于安全考虑，禁止执行包含 “" + bad + "” 的语句（会读写服务器文件）");
			}
		}

		String verb = firstKeyword(body);
		if (verb.isEmpty()) {
			return Outcome.blocked("无法识别的语句");
		}
		if (BLOCKED_VERBS.contains(verb)) {
			return Outcome.blocked("不支持执行 " + verb + " 语句");
		}

		Kind kind;
		if (SELECT_VERBS.contains(verb)) {
			kind = Kind.SELECT;
		}
		else if (DML_VERBS.contains(verb)) {
			kind = Kind.DML;
		}
		else if (DDL_VERBS.contains(verb)) {
			kind = Kind.DDL;
		}
		else {
			return Outcome.blocked("不支持的语句类型：" + verb);
		}

		if (!admin && kind != Kind.SELECT) {
			String hint = kind == Kind.DDL ? "建表/改表请切到“管理模式”"
					: "写数据请切到“管理模式”；也可以用右侧 AI 造数，不用手写 INSERT";
			return Outcome.blocked("查询模式只允许 SELECT / WITH … SELECT。" + hint);
		}
		if (kind != Kind.SELECT && !properties.isAdminEnabled()) {
			return Outcome.blocked("服务端已关闭写操作（sqllab.admin-enabled=false）");
		}

		if (verb.equals("DROP") || verb.equals("TRUNCATE")) {
			return Outcome.confirm(kind, body, "即将执行 " + verb + "，该操作不可逆，数据将被删除。确定继续吗？");
		}
		if ((verb.equals("DELETE") || verb.equals("UPDATE")) && !lower.contains(" where ")) {
			return Outcome.confirm(kind, body, "这条 " + verb + " 没有 WHERE 条件，会作用于整张表。确定继续吗？");
		}
		return Outcome.pass(kind, body);
	}

	/** H2 不支持的 MySQL 便利命令 -> 翻译为 information_schema 查询 */
	private Optional<String> translate(String body, String schema) {
		String lower = body.trim().toLowerCase(Locale.ROOT);
		String schemaLiteral = "'" + schema.toUpperCase(Locale.ROOT) + "'";
		if (lower.matches("show\\s+tables?(\\s+from\\s+\\w+)?")) {
			return Optional.of("SELECT TABLE_NAME AS table_name, TABLE_TYPE AS table_type "
					+ "FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = " + schemaLiteral
					+ " ORDER BY TABLE_NAME");
		}
		if (lower.matches("show\\s+(databases|schemas)")) {
			return Optional.of("SELECT SCHEMA_NAME AS schema_name FROM INFORMATION_SCHEMA.SCHEMATA "
					+ "WHERE SCHEMA_NAME NOT IN ('INFORMATION_SCHEMA') ORDER BY SCHEMA_NAME");
		}
		if (lower.matches("(show\\s+columns\\s+from|desc|describe)\\s+`?\\w+`?")) {
			String[] parts = body.trim().split("\\s+");
			String table = parts[parts.length - 1].replace("`", "");
			return Optional.of("SELECT COLUMN_NAME AS column_name, TYPE_NAME AS type_name, "
					+ "CHARACTER_MAXIMUM_LENGTH AS max_length, IS_NULLABLE AS is_nullable, REMARKS AS comment "
					+ "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = " + schemaLiteral
					+ " AND TABLE_NAME = '" + table.toUpperCase(Locale.ROOT) + "' ORDER BY ORDINAL_POSITION");
		}
		return Optional.empty();
	}

	private String firstKeyword(String body) {
		String t = body.trim();
		String upper = t.toUpperCase(Locale.ROOT);
		if (upper.startsWith("WITH")) {
			return upper.contains(" SELECT") ? "WITH" : "";
		}
		int i = 0;
		while (i < t.length() && !Character.isLetter(t.charAt(i))) {
			i++;
		}
		int j = i;
		while (j < t.length() && (Character.isLetterOrDigit(t.charAt(j)) || t.charAt(j) == '_')) {
			j++;
		}
		return t.substring(i, j).toUpperCase(Locale.ROOT);
	}
}
