package com.sharefo.aiworkbench.sqllab.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.sharefo.aiworkbench.sqllab.config.LlmProperties;
import com.sharefo.aiworkbench.sqllab.dto.SchemaResp;

@Component
public class PromptBuilder {

	private static final String DIALECT_NOTE =
			"目标数据库是 H2 2.x，运行在 MySQL 兼容模式下，整体按 MySQL 写法即可（支持 LIMIT、IFNULL、CASE WHEN、"
					+ "CONCAT、ROUND、LEFT、SUBSTRING、CAST(x AS DATE)、YEAR()/MONTH()、"
					+ "窗口函数 ROW_NUMBER()/RANK()/SUM() OVER）。"
					+ "但有两个 MySQL 函数在这里不可用，务必避开："
					+ "① 不要写 DATE_FORMAT，改用 FORMATDATETIME(字段, 'yyyy-MM')；"
					+ "② 不要写 DATE_SUB(NOW(), INTERVAL 7 DAY)，"
					+ "取最近 N 天一律写成 字段 >= DATEADD('DAY', -N, CURRENT_TIMESTAMP) 或 字段 >= CURRENT_DATE - N，"
					+ "算日期差用 TIMESTAMPDIFF('DAY', a, b)。";

	private static final String FIELD_RULES =
			"\n字段归属规则（必须严格遵守，否则 SQL 会执行失败）：\n"
					+ "1. 每个字段都要写成「表别名.字段名」，且该字段必须真实属于这个别名对应的表，"
					+ "绝不能把 A 表的字段挂到 B 表的别名后面（例如 dim_date 没有 channel_id，就不能写 d.channel_id）。\n"
					+ "2. SELECT、GROUP BY、ORDER BY、WHERE 里出现的字段，必须来自 FROM 或 JOIN 中出现过的表。\n"
					+ "3. 只 JOIN 真正需要的表。做时间筛选（最近 N 天）直接用 WHERE 条件即可，不要为此去 JOIN 日期维表。\n"
					+ "4. 输出前自查一遍：每一个「别名.字段」是否都能在给定表结构里找到。\n";

	private static final String SYSTEM_GENERATE =
			"你是一名资深数据分析师，服务于不懂 SQL 的运营同学。"
					+ DIALECT_NOTE
					+ "只能使用下面给出的表与字段，绝不允许臆造字段或表名。"
					+ FIELD_RULES
					+ "输出格式严格如下：先给一个 ```sql 代码块，代码块内只放一条可直接执行的 SQL 语句（结尾带分号），"
					+ "代码块之后用不超过 4 行中文解释这条 SQL 在做什么、每个关键字段的含义。不要输出任何多余内容。";

	private static final String SYSTEM_FIX =
			"你是一名 SQL 调试专家。" + DIALECT_NOTE
					+ "根据报错信息修正用户 SQL，只使用下面给出的表与字段。"
					+ FIELD_RULES
					+ "输出格式：先一个 ```sql 代码块放修正后的完整 SQL，代码块之后用不超过 3 行中文说明错在哪、改了什么。";

	private static final String SYSTEM_EXPLAIN =
			"你是一名耐心的 SQL 老师，面向完全不懂 SQL 的运营同学讲人话。"
					+ "请按「这段 SQL 在干什么 / 关键关键词怎么理解 / 结果怎么读」三段话讲解，每段不超过 4 行，用中文，不要输出 SQL 代码块。";

	private static final String SYSTEM_SEED = buildSeedSystem();

	private final LlmProperties properties;

	public PromptBuilder(LlmProperties properties) {
		this.properties = properties;
	}

	public String schemaText(SchemaResp schema, String hint) {
		if (schema == null || schema.getTables().isEmpty()) {
			return "（当前空间还没有任何表）";
		}
		List<SchemaResp.TableMeta> tables = new ArrayList<>(schema.getTables());
		int limit = Math.max(1, properties.getMaxContextTables());
		if (tables.size() > limit && hint != null && !hint.isBlank()) {
			String key = hint.toLowerCase(Locale.ROOT);
			tables.sort(Comparator.comparingInt(t -> matchScore(t, key)));
			tables = new ArrayList<>(tables.subList(0, limit));
			tables.sort(Comparator.comparing(t -> t.getName()));
		}
		StringBuilder sb = new StringBuilder("当前可用的表（共 ").append(schema.getTables().size()).append(" 张）：\n");
		for (SchemaResp.TableMeta t : tables) {
			sb.append("- ").append(t.getName());
			if (t.getComment() != null && !t.getComment().isBlank()) {
				sb.append("（").append(t.getComment()).append("）");
			}
			sb.append("，约 ").append(t.getRowCount()).append(" 行\n");
			for (SchemaResp.ColumnInfo c : t.getColumns()) {
				sb.append("    ").append(c.getName()).append(" ").append(c.getType());
				if (c.isPrimaryKey()) {
					sb.append(" [主键]");
				}
				if (!c.isNullable()) {
					sb.append(" [非空]");
				}
				if (c.getComment() != null && !c.getComment().isBlank()) {
					sb.append(" 注释:").append(c.getComment());
				}
				sb.append("\n");
			}
		}
		return sb.toString();
	}

	private int matchScore(SchemaResp.TableMeta t, String key) {
		int score = 0;
		String name = t.getName().toLowerCase(Locale.ROOT);
		for (String token : key.split("[^\\u4e00-\\u9fa5a-zA-Z0-9]+")) {
			if (token.isBlank()) {
				continue;
			}
			String tk = token.toLowerCase(Locale.ROOT);
			if (name.contains(tk)) {
				score -= 3;
			}
			if (t.getComment() != null && t.getComment().contains(token)) {
				score -= 2;
			}
			for (SchemaResp.ColumnInfo c : t.getColumns()) {
				if (c.getName().contains(tk)) {
					score -= 1;
				}
				if (c.getComment() != null && c.getComment().contains(token)) {
					score -= 1;
				}
			}
		}
		return score;
	}

	public List<Map<String, Object>> generateMessages(String schemaText, String question) {
		return List.of(msg("system", SYSTEM_GENERATE),
				msg("user", "库表结构：\n" + schemaText + "\n\n需求：" + question));
	}

	public List<Map<String, Object>> fixMessages(String schemaText, String sql, String error) {
		return List.of(msg("system", SYSTEM_FIX),
				msg("user", "库表结构：\n" + schemaText + "\n\n用户 SQL：\n" + sql + "\n\n报错信息：\n" + error));
	}

	public List<Map<String, Object>> explainMessages(String sql) {
		return List.of(msg("system", SYSTEM_EXPLAIN), msg("user", "请讲解这条 SQL：\n" + sql));
	}

	public List<Map<String, Object>> seedPlanMessages(String schemaText, String instruction, int rowCount) {
		return List.of(msg("system", SYSTEM_SEED),
				msg("user", "当前已有表：\n" + schemaText + "\n\n需要造的数据：" + instruction
						+ "\n\n默认生成行数：" + rowCount + "（如用户有明确要求按用户说的来）"));
	}

	public static Map<String, Object> msg(String role, String content) {
		return Map.of("role", role, "content", content);
	}

	private static String buildSeedSystem() {
		return "你是一个造数方案生成器。用户会用中文描述想要的一张数据表，你只输出一份 JSON 造数方案，不要任何解释文字、不要 Markdown 围栏。\n"
				+ "JSON 结构：\n"
				+ "{\n"
				+ "  \"table\": \"小写英文表名\",\n"
				+ "  \"comment\": \"表中文说明\",\n"
				+ "  \"rowCount\": 1000,\n"
				+ "  \"dropIfExists\": true,\n"
				+ "  \"columns\": [\n"
				+ "    {\"name\":\"id\",\"type\":\"BIGINT\",\"comment\":\"主键\",\"primaryKey\":true,\"rule\":{\"kind\":\"sequence\"}},\n"
				+ "    {\"name\":\"amount\",\"type\":\"DECIMAL(12,2)\",\"comment\":\"金额\",\"rule\":{\"kind\":\"decimal_range\",\"min\":10,\"max\":5000,\"scale\":2}},\n"
				+ "    {\"name\":\"created_at\",\"type\":\"TIMESTAMP\",\"comment\":\"创建时间\",\"rule\":{\"kind\":\"datetime_range\",\"start\":\"2025-01-01\",\"end\":\"2026-12-31\"}}\n"
				+ "  ]\n"
				+ "}\n"
				+ "字段类型只能是：BIGINT / INT / TINYINT / DOUBLE / BOOLEAN / DATE / TIMESTAMP / TEXT / DECIMAL(p,s) / VARCHAR(n)。\n"
				+ "rule.kind 只能是以下之一：\n"
				+ " sequence（自增，可选 start）\n"
				+ " const（固定值，参数 value）\n"
				+ " int_range（参数 min/max）\n"
				+ " decimal_range（参数 min/max/scale）\n"
				+ " date_range（参数 start/end，格式 YYYY-MM-DD）\n"
				+ " datetime_range（参数 start/end）\n"
				+ " enum（参数 values 数组、可选 weights 数组表示权重）\n"
				+ " ref（参数 table/column，从已有表取真实值，用于做外键关联）\n"
				+ " person_name / phone / email / city / product_name / order_no / uuid\n"
				+ " template（参数 value，支持占位符 {{yyyyMM}} {{yyyyMMdd}} {{seq:4}} {{uuid}} {{row}}）\n"
				+ " bool（可选 trueRate）\n"
				+ " nullable（参数 rate 表示为空概率，inner 里包一个其它规则）\n"
				+ "要求：第一个字段通常命名为 id 且 primaryKey=true；枚举类字段优先用 enum 给出贴近业务的中文取值；"
				+ "需要与已有表关联的字段优先用 ref，ref 的 table 必须是用户列出的已有表里真实存在的名字，"
				+ "逐字照抄，不要自己改成单数或复数；时间字段用 date_range 或 datetime_range；"
				+ "字符串字段优先用 enum 或 template，避免全是无意义的 value-1。\n"
				+ "严禁输出数据行、严禁把列名写成中文，只输出上面这一个 JSON 对象。";
	}
}
