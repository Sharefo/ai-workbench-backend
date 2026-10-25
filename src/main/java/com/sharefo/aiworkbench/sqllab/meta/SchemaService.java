package com.sharefo.aiworkbench.sqllab.meta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.sharefo.aiworkbench.sqllab.dto.SchemaResp;
import com.sharefo.aiworkbench.sqllab.exec.SqlExecutor;

@Service
public class SchemaService {

	private static final long TTL = 30_000L;

	private final JdbcTemplate jdbc;

	private final Map<String, Entry> cache = new ConcurrentHashMap<>();

	public SchemaService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public SchemaResp load(String sessionId, String schema) {
		Entry hit = cache.get(sessionId);
		long now = System.currentTimeMillis();
		if (hit != null && now - hit.at < TTL) {
			return hit.value;
		}
		String upperSchema = schema.toUpperCase(Locale.ROOT);
		Map<String, String> comments = new HashMap<>();
		List<Map<String, Object>> tables = jdbc.queryForList(
				"SELECT TABLE_NAME, REMARKS FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ?"
						+ " AND TABLE_TYPE IN ('BASE TABLE', 'TABLE') ORDER BY TABLE_NAME", upperSchema);
		Map<String, Boolean> pk = primaryKeys(upperSchema);
		Map<String, List<Map<String, Object>>> columnsByTable = new HashMap<>();
		List<Map<String, Object>> cols = jdbc.queryForList(
				"SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION,"
						+ " NUMERIC_SCALE, IS_NULLABLE, REMARKS FROM INFORMATION_SCHEMA.COLUMNS"
						+ " WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME, ORDINAL_POSITION", upperSchema);
		for (Map<String, Object> c : cols) {
			String table = String.valueOf(c.get("TABLE_NAME"));
			columnsByTable.computeIfAbsent(table, k -> new ArrayList<>()).add(c);
			comments.put(table, text(c.get("REMARKS")));
		}

		SchemaResp resp = new SchemaResp();
		resp.setSessionId(sessionId);
		for (Map<String, Object> t : tables) {
			String rawName = String.valueOf(t.get("TABLE_NAME"));
			SchemaResp.TableMeta meta = new SchemaResp.TableMeta();
			meta.setName(rawName.toLowerCase(Locale.ROOT));
			meta.setComment(text(t.get("REMARKS")));
			meta.setRowCount(count(schema, rawName));
			for (Map<String, Object> c : columnsByTable.getOrDefault(rawName, List.of())) {
				String colName = String.valueOf(c.get("COLUMN_NAME"));
				String type = readableType(text(c.get("DATA_TYPE")), c.get("CHARACTER_MAXIMUM_LENGTH"),
						c.get("NUMERIC_PRECISION"), c.get("NUMERIC_SCALE"));
				meta.getColumns().add(new SchemaResp.ColumnInfo(colName.toLowerCase(Locale.ROOT), type,
						"YES".equalsIgnoreCase(String.valueOf(c.get("IS_NULLABLE"))),
						Boolean.TRUE.equals(pk.get(rawName + "." + colName)), text(c.get("REMARKS"))));
			}
			resp.getTables().add(meta);
		}
		cache.put(sessionId, new Entry(now, resp));
		return resp;
	}

	public void invalidate(String sessionId) {
		cache.remove(sessionId);
	}

	public List<List<Object>> sample(String schema, String table, int n) {
		String safeTable = table.replace("\"", "");
		String sql = "SELECT * FROM \"" + schema.toUpperCase(Locale.ROOT) + "\".\"" + safeTable.toUpperCase(Locale.ROOT)
				+ "\" LIMIT " + Math.min(Math.max(n, 1), 50);
		return jdbc.query(sql, (rs) -> {
			List<List<Object>> rows = new ArrayList<>();
			int cols = rs.getMetaData().getColumnCount();
			List<String> names = new ArrayList<>();
			for (int i = 1; i <= cols; i++) {
				names.add(rs.getMetaData().getColumnLabel(i));
			}
			List<Object> header = new ArrayList<>(names);
			rows.add(header);
			while (rs.next()) {
				List<Object> row = new ArrayList<>();
				for (int i = 1; i <= cols; i++) {
					row.add(SqlExecutor.convert(rs.getObject(i)));
				}
				rows.add(row);
			}
			return rows;
		});
	}

	private long count(String schema, String table) {
		try {
			Long n = jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema.toUpperCase(Locale.ROOT) + "\".\""
					+ table + "\"", Long.class);
			return n == null ? 0 : n;
		}
		catch (Exception e) {
			return -1;
		}
	}

	private Map<String, Boolean> primaryKeys(String upperSchema) {
		Map<String, Boolean> out = new HashMap<>();
		try {
			List<Map<String, Object>> rows = jdbc.queryForList(
					"SELECT k.TABLE_NAME, k.COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k"
							+ " JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS c"
							+ " ON c.CONSTRAINT_NAME = k.CONSTRAINT_NAME AND c.TABLE_SCHEMA = k.TABLE_SCHEMA"
							+ " WHERE k.TABLE_SCHEMA = ? AND c.CONSTRAINT_TYPE = 'PRIMARY KEY'", upperSchema);
			for (Map<String, Object> r : rows) {
				out.put(r.get("TABLE_NAME") + "." + r.get("COLUMN_NAME"), true);
			}
		}
		catch (Exception ignored) {
			// 取不到主键信息时降级为「无主键」，不影响使用
		}
		return out;
	}

	private String readableType(String type, Object length, Object precision, Object scale) {
		String t = type;
		if ("CHARACTER VARYING".equalsIgnoreCase(t) || "VARCHAR".equalsIgnoreCase(t)
				|| "CHARACTER LARGE OBJECT".equalsIgnoreCase(t) || "TEXT".equalsIgnoreCase(t)) {
			long len = number(length, 0);
			return len > 0 && len < 1_000_000 ? "VARCHAR(" + len + ")" : "TEXT";
		}
		if ("NUMERIC".equalsIgnoreCase(t) || "DECIMAL".equalsIgnoreCase(t)) {
			return "DECIMAL(" + number(precision, 10) + "," + number(scale, 0) + ")";
		}
		return t;
	}

	private static long number(Object o, long fallback) {
		if (o == null) {
			return fallback;
		}
		try {
			return Long.parseLong(String.valueOf(o));
		}
		catch (Exception e) {
			return fallback;
		}
	}

	private static String text(Object o) {
		return o == null ? "" : String.valueOf(o);
	}

	private static class Entry {

		final long at;

		final SchemaResp value;

		Entry(long at, SchemaResp value) {
			this.at = at;
			this.value = value;
		}
	}
}
