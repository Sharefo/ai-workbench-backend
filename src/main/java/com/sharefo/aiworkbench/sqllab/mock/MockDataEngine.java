package com.sharefo.aiworkbench.sqllab.mock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.sharefo.aiworkbench.sqllab.dto.SeedPlan;

/**
 * 造数引擎：只接受「表结构 + 字段生成规则」，在本地生成数据后批量写入，
 * 避免让大模型直接吐几万行 INSERT。
 */
@Component
public class MockDataEngine {

	private static final Pattern NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]{0,40}");

	private static final Pattern TYPE = Pattern.compile(
			"(?i)^(BIGINT|INT|INTEGER|TINYINT|SMALLINT|DOUBLE|FLOAT|REAL|BOOLEAN|DATE|TIMESTAMP|DATETIME|TEXT"
					+ "|DECIMAL\\(\\s*\\d+\\s*(,\\s*\\d+\\s*)?\\)|VARCHAR\\(\\s*\\d+\\s*\\)|CHAR\\(\\s*\\d+\\s*\\))$");

	private static final int MAX_ROWS = 2_000_000;

	private static final String[] SURNAMES = {"张", "王", "李", "赵", "刘", "陈", "杨", "黄", "周", "吴", "徐", "孙", "马", "朱"};

	private static final String[] GIVEN = {"雨桐", "梓涵", "浩然", "子轩", "欣怡", "思远", "嘉怡", "宇轩", "诗涵", "俊杰",
			"梦琪", "晨曦", "若曦", "博文"};

	private static final String[] CITIES = {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安", "南京", "重庆",
			"苏州", "长沙", "青岛", "天津", "郑州", "合肥"};

	private static final String[] NOUNS = {"轻奢", "基础", "限量", "联名", "爆款", "经典", "新品", "优选"};

	private static final String[] GOODS = {"保温杯", "连衣裙", "运动鞋", "面膜", "咖啡豆", "蓝牙耳机", "收纳箱", "电动牙刷",
			"坚果礼盒", "纯棉T恤", "空气炸锅", "儿童积木"};

	private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final JdbcTemplate jdbc;

	public MockDataEngine(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public static class ApplyResult {

		private final String ddl;

		private final int inserted;

		ApplyResult(String ddl, int inserted) {
			this.ddl = ddl;
			this.inserted = inserted;
		}

		public String getDdl() {
			return ddl;
		}

		public int getInserted() {
			return inserted;
		}
	}

	public ApplyResult apply(String schema, SeedPlan plan) {
		validate(plan);
		List<SeedPlan.SeedColumn> columns = plan.getColumns();
		StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(plan.getTable()).append(" (");
		for (int i = 0; i < columns.size(); i++) {
			SeedPlan.SeedColumn c = columns.get(i);
			if (i > 0) {
				ddl.append(", ");
			}
			ddl.append(c.getName()).append(" ").append(c.getType());
			if (c.isPrimaryKey()) {
				ddl.append(" NOT NULL");
			}
		}
		if (columns.stream().anyMatch(SeedPlan.SeedColumn::isPrimaryKey)) {
			ddl.append(", PRIMARY KEY (")
					.append(String.join(", ", columns.stream().filter(SeedPlan.SeedColumn::isPrimaryKey)
							.map(SeedPlan.SeedColumn::getName).toList()))
					.append(")");
		}
		ddl.append(")");

		Context ctx = new Context(schema);
		Random rnd = new Random(20261026L);
		int rows = plan.getRowCount();

		jdbc.execute((Connection conn) -> {
			Statement boot = conn.createStatement();
			boot.execute("SET SCHEMA " + schema);
			boot.close();
			try {
				Statement st = conn.createStatement();
				if (plan.isDropIfExists()) {
					st.execute("DROP TABLE IF EXISTS " + plan.getTable());
				}
				st.execute(ddl.toString());
				if (plan.getComment() != null && !plan.getComment().isBlank()) {
					st.execute("COMMENT ON TABLE " + plan.getTable() + " IS '" + escape(plan.getComment()) + "'");
				}
				for (SeedPlan.SeedColumn c : columns) {
					if (c.getComment() != null && !c.getComment().isBlank()) {
						st.execute("COMMENT ON COLUMN " + plan.getTable() + "." + c.getName() + " IS '"
								+ escape(c.getComment()) + "'");
					}
				}
				st.close();

				String insert = buildInsert(plan);
				try (PreparedStatement ps = conn.prepareStatement(insert)) {
					for (int r = 0; r < rows; r++) {
						for (int c = 0; c < columns.size(); c++) {
							SeedPlan.SeedColumn col = columns.get(c);
							Object v = generate(col, r, rnd, ctx);
							bind(ps, c + 1, v, col.getType());
						}
						ps.addBatch();
						if (r % 1000 == 999) {
							ps.executeBatch();
						}
					}
					ps.executeBatch();
				}
			}
			finally {
				Statement restore = conn.createStatement();
				restore.execute("SET SCHEMA PUBLIC");
				restore.close();
			}
			return null;
		});
		return new ApplyResult(ddl.toString(), rows);
	}

	private void validate(SeedPlan plan) {
		if (plan == null || plan.getTable() == null || !NAME.matcher(plan.getTable()).matches()) {
			throw new IllegalArgumentException("表名不合法，只允许字母、数字、下划线且以字母开头");
		}
		if (plan.getColumns() == null || plan.getColumns().isEmpty()) {
			throw new IllegalArgumentException("至少要有一个字段");
		}
		for (SeedPlan.SeedColumn c : plan.getColumns()) {
			if (c.getName() == null || !NAME.matcher(c.getName()).matches()) {
				throw new IllegalArgumentException("字段名不合法：" + c.getName());
			}
			if (c.getType() == null || !TYPE.matcher(c.getType().trim()).matches()) {
				throw new IllegalArgumentException("字段 " + c.getName() + " 的类型不支持：" + c.getType());
			}
		}
		if (plan.getRowCount() < 1 || plan.getRowCount() > MAX_ROWS) {
			throw new IllegalArgumentException("行数必须在 1 到 " + MAX_ROWS + " 之间");
		}
	}

	private String buildInsert(SeedPlan plan) {
		StringBuilder sb = new StringBuilder("INSERT INTO ").append(plan.getTable()).append(" (");
		sb.append(String.join(", ", plan.getColumns().stream().map(SeedPlan.SeedColumn::getName).toList()));
		sb.append(") VALUES (");
		for (int i = 0; i < plan.getColumns().size(); i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append("?");
		}
		return sb.append(")").toString();
	}

	private Object generate(SeedPlan.SeedColumn col, int row, Random rnd, Context ctx) {
		Map<String, Object> rule = col.getRule() == null ? Map.of() : col.getRule();
		String kind = str(rule.get("kind"));
		if (kind.isBlank()) {
			kind = guessKind(col.getType());
		}
		if ("nullable".equals(kind)) {
			double rate = dbl(rule.get("rate"), 0.2);
			if (rnd.nextDouble() < rate) {
				return null;
			}
			Object inner = rule.get("inner");
			Map<String, Object> innerRule = inner instanceof Map ? cast(inner) : Map.of();
			return byKind(str(innerRule.get("kind")).isBlank() ? guessKind(col.getType()) : str(innerRule.get("kind")),
					innerRule, row, rnd, ctx, col.getType());
		}
		return byKind(kind, rule, row, rnd, ctx, col.getType());
	}

	private Object byKind(String kind, Map<String, Object> rule, int row, Random rnd, Context ctx, String type) {
		switch (kind) {
			case "sequence":
				return (long) (lng(rule.get("start"), 1) + row);
			case "const":
				return rule.get("value");
			case "int_range":
				return lng(rule.get("min"), 1) + rnd.nextInt((int) Math.max(1, lng(rule.get("max"), 100)
						- lng(rule.get("min"), 1) + 1));
			case "decimal_range":
				double min = dbl(rule.get("min"), 0);
				double max = dbl(rule.get("max"), 1000);
				int scale = (int) lng(rule.get("scale"), 2);
				double v = min + rnd.nextDouble() * (max - min);
				return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
			case "date_range":
				return randomDate(rnd, rule);
			case "datetime_range":
				return randomDateTime(rnd, rule);
			case "enum":
				return pickEnum(rule, rnd);
			case "ref":
				return pickRef(rule, rnd, ctx);
			case "person_name":
				return SURNAMES[rnd.nextInt(SURNAMES.length)] + GIVEN[rnd.nextInt(GIVEN.length)];
			case "phone":
				return "1" + (3 + rnd.nextInt(6)) + String.format("%09d", rnd.nextInt(1000000000));
			case "email":
				return "user" + (row + 1) + "@example.com";
			case "city":
				return CITIES[rnd.nextInt(CITIES.length)];
			case "product_name":
				return NOUNS[rnd.nextInt(NOUNS.length)] + GOODS[rnd.nextInt(GOODS.length)];
			case "order_no":
				return String.format("NO%06d%04d", rnd.nextInt(1000000), row + 1);
			case "uuid":
				return UUID.randomUUID().toString().substring(0, Math.min(32, Math.max(8, (int) lng(rule.get("len"), 32))));
			case "template":
				return template(str(rule.get("value")), row, rnd);
			case "bool":
			case "boolean":
				return rnd.nextDouble() < dbl(rule.get("trueRate"), 0.5);
			default:
				return fallback(type, row, rnd);
		}
	}

	private Object fallback(String type, int row, Random rnd) {
		String t = type.toUpperCase(Locale.ROOT);
		if (t.startsWith("VARCHAR") || t.startsWith("CHAR") || t.equals("TEXT")) {
			return "value-" + (row + 1);
		}
		if (t.equals("DATE")) {
			return LocalDate.now().minusDays(rnd.nextInt(365));
		}
		if (t.equals("TIMESTAMP") || t.equals("DATETIME")) {
			return LocalDateTime.now().minusMinutes(rnd.nextInt(525600));
		}
		if (t.equals("BOOLEAN")) {
			return rnd.nextBoolean();
		}
		if (t.startsWith("DECIMAL")) {
			return BigDecimal.valueOf(Math.round(rnd.nextDouble() * 100000) / 100.0).setScale(2, RoundingMode.HALF_UP);
		}
		return (long) (row + 1);
	}

	private String guessKind(String type) {
		String t = type.toUpperCase(Locale.ROOT);
		if (t.startsWith("VARCHAR") || t.startsWith("CHAR") || t.equals("TEXT")) {
			return "template";
		}
		if (t.equals("DATE")) {
			return "date_range";
		}
		if (t.equals("TIMESTAMP") || t.equals("DATETIME")) {
			return "datetime_range";
		}
		if (t.equals("BOOLEAN")) {
			return "bool";
		}
		if (t.startsWith("DECIMAL") || t.equals("DOUBLE") || t.equals("FLOAT") || t.equals("REAL")) {
			return "decimal_range";
		}
		return "int_range";
	}

	private LocalDate randomDate(Random rnd, Map<String, Object> rule) {
		LocalDate start = parseDate(rule.get("start"), LocalDate.now().minusYears(1));
		LocalDate end = parseDate(rule.get("end"), LocalDate.now());
		if (end.isBefore(start)) {
			end = start;
		}
		long days = end.toEpochDay() - start.toEpochDay() + 1;
		return start.plusDays(rnd.nextInt((int) Math.max(1, days)));
	}

	private LocalDateTime randomDateTime(Random rnd, Map<String, Object> rule) {
		LocalDate day = randomDate(rnd, rule);
		return day.atTime(rnd.nextInt(24), rnd.nextInt(60), rnd.nextInt(60));
	}

	private LocalDate parseDate(Object o, LocalDate fallback) {
		if (o == null) {
			return fallback;
		}
		try {
			return LocalDate.parse(String.valueOf(o).trim().substring(0, Math.min(10, String.valueOf(o).trim().length())));
		}
		catch (Exception e) {
			return fallback;
		}
	}

	private Object pickEnum(Map<String, Object> rule, Random rnd) {
		List<String> values = new ArrayList<>();
		Object raw = rule.get("values");
		if (raw instanceof List<?> list) {
			for (Object o : list) {
				values.add(String.valueOf(o));
			}
		}
		if (values.isEmpty()) {
			return "值" + (rnd.nextInt(3) + 1);
		}
		Object w = rule.get("weights");
		if (w instanceof List<?> weights && weights.size() == values.size()) {
			int total = 0;
			for (Object o : weights) {
				total += Math.max(0, (int) lng(o, 1));
			}
			if (total > 0) {
				int roll = rnd.nextInt(total);
				for (int i = 0; i < values.size(); i++) {
					roll -= Math.max(0, (int) lng(weights.get(i), 1));
					if (roll < 0) {
						return values.get(i);
					}
				}
			}
		}
		return values.get(rnd.nextInt(values.size()));
	}

	private Object pickRef(Map<String, Object> rule, Random rnd, Context ctx) {
		String table = str(rule.get("table"));
		String column = str(rule.get("column"));
		if (table.isBlank() || column.isBlank()) {
			return null;
		}
		List<Object> values = ctx.refValues(table, column);
		if (values.isEmpty()) {
			throw new IllegalArgumentException("引用的表 " + table + "." + column + " 没有可取值，请换一个字段或先造那张表");
		}
		return values.get(rnd.nextInt(values.size()));
	}

	private String template(String value, int row, Random rnd) {
		String out = value == null || value.isBlank() ? "VAL-{{seq:4}}" : value;
		out = out.replace("{{yyyyMM}}", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM")));
		out = out.replace("{{yyyyMMdd}}", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));
		out = out.replace("{{uuid}}", UUID.randomUUID().toString().substring(0, 8));
		int idx = out.indexOf("{{seq:");
		if (idx >= 0) {
			int end = out.indexOf("}}", idx);
			if (end > idx) {
				int width = Integer.parseInt(out.substring(idx + 6, end).trim());
				out = out.substring(0, idx) + String.format("%0" + width + "d", row + 1) + out.substring(end + 2);
			}
		}
		return out.replace("{{row}}", String.valueOf(row + 1));
	}

	private void bind(PreparedStatement ps, int index, Object v, String type) throws SQLException {
		String t = type.toUpperCase(Locale.ROOT);
		if (v == null) {
			ps.setNull(index, t.startsWith("VARCHAR") || t.startsWith("CHAR") || t.equals("TEXT")
					? java.sql.Types.VARCHAR : t.equals("DATE") ? java.sql.Types.DATE
					: (t.equals("TIMESTAMP") || t.equals("DATETIME")) ? java.sql.Types.TIMESTAMP
					: t.startsWith("DECIMAL") || t.equals("DOUBLE") || t.equals("FLOAT") || t.equals("REAL")
					? java.sql.Types.DECIMAL : java.sql.Types.BIGINT);
			return;
		}
		if (v instanceof LocalDate ld) {
			ps.setDate(index, Date.valueOf(ld));
		}
		else if (v instanceof LocalDateTime ldt) {
			ps.setTimestamp(index, Timestamp.valueOf(ldt));
		}
		else if (v instanceof BigDecimal bd) {
			ps.setBigDecimal(index, bd);
		}
		else if (v instanceof Long l) {
			ps.setLong(index, l);
		}
		else if (v instanceof Integer i) {
			ps.setInt(index, i);
		}
		else if (v instanceof Double d) {
			ps.setDouble(index, d);
		}
		else if (v instanceof Boolean b) {
			ps.setBoolean(index, b);
		}
		else {
			ps.setString(index, String.valueOf(v));
		}
	}

	private static String escape(String s) {
		return s.replace("'", "''");
	}

	private static String str(Object o) {
		return o == null ? "" : String.valueOf(o);
	}

	private static long lng(Object o, long fallback) {
		if (o == null) {
			return fallback;
		}
		if (o instanceof Number n) {
			return n.longValue();
		}
		try {
			return Long.parseLong(String.valueOf(o).trim());
		}
		catch (Exception e) {
			return fallback;
		}
	}

	private static double dbl(Object o, double fallback) {
		if (o == null) {
			return fallback;
		}
		if (o instanceof Number n) {
			return n.doubleValue();
		}
		try {
			return Double.parseDouble(String.valueOf(o).trim());
		}
		catch (Exception e) {
			return fallback;
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> cast(Object o) {
		return (Map<String, Object>) o;
	}

	private class Context {

		private final String schema;

		private final Map<String, List<Object>> refs = new HashMap<>();

		Context(String schema) {
			this.schema = schema;
		}

		List<Object> refValues(String table, String column) {
			String key = table.toLowerCase(Locale.ROOT) + "." + column.toLowerCase(Locale.ROOT);
			return refs.computeIfAbsent(key, k -> {
				String sql = "SELECT DISTINCT " + column + " FROM \"" + schema.toUpperCase(Locale.ROOT) + "\".\""
						+ table.toUpperCase(Locale.ROOT) + "\" WHERE " + column + " IS NOT NULL LIMIT 10000";
				List<Object> out = new ArrayList<>();
				jdbc.query(sql, (ResultSet rs) -> {
					while (rs.next()) {
						out.add(rs.getObject(1));
					}
					return null;
				});
				return out;
			});
		}
	}
}
