package com.sharefo.aiworkbench.sqllab.exec;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.sharefo.aiworkbench.sqllab.config.SqlLabProperties;
import com.sharefo.aiworkbench.sqllab.dto.ExecuteReq;
import com.sharefo.aiworkbench.sqllab.dto.ExecuteResp;
import com.sharefo.aiworkbench.sqllab.guard.SqlSafetyGuard;
import com.sharefo.aiworkbench.sqllab.meta.SchemaService;

@Component
public class SqlExecutor {

	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final org.springframework.jdbc.core.JdbcTemplate jdbc;

	private final SqlSafetyGuard guard;

	private final SqlLabProperties properties;

	private final SchemaService schemaService;

	public SqlExecutor(org.springframework.jdbc.core.JdbcTemplate jdbc, SqlSafetyGuard guard,
			SqlLabProperties properties, SchemaService schemaService) {
		this.jdbc = jdbc;
		this.guard = guard;
		this.properties = properties;
		this.schemaService = schemaService;
	}

	public SqlSafetyGuard getGuard() {
		return guard;
	}

	public ExecuteResp run(String sessionId, String schema, ExecuteReq req) {
		ExecuteResp resp = new ExecuteResp();
		long start = System.currentTimeMillis();
		List<String> statements = guard.split(req.getSql());
		boolean admin = "ADMIN".equalsIgnoreCase(req.getMode());
		int requested = req.getMaxRows() == null || req.getMaxRows() <= 0
				? properties.getMaxRows() : req.getMaxRows();
		int maxRows = Math.min(requested, properties.getHardMaxRows());

		List<String> prepared = new ArrayList<>();
		List<SqlSafetyGuard.Kind> kinds = new ArrayList<>();
		for (String raw : statements) {
			SqlSafetyGuard.Outcome outcome = guard.inspect(raw, admin, schema);
			if (outcome.getSql() == null && outcome.getBlockReason() == null) {
				continue;
			}
			if (outcome.getKind() == SqlSafetyGuard.Kind.BLOCKED) {
				throw new GuardException(outcome.getBlockReason());
			}
			if (outcome.isConfirmRequired() && !req.isConfirmed()) {
				resp.setNeedConfirm(true);
				resp.setConfirmMessage(outcome.getConfirmMessage());
				resp.setElapsedMs(System.currentTimeMillis() - start);
				return resp;
			}
			prepared.add(outcome.getSql());
			kinds.add(outcome.getKind());
		}
		if (prepared.isEmpty()) {
			resp.setMessage("没有可执行的语句");
			resp.setElapsedMs(System.currentTimeMillis() - start);
			return resp;
		}

		boolean anyWrite = kinds.stream().anyMatch(k -> k != SqlSafetyGuard.Kind.SELECT);
		jdbc.execute((Connection conn) -> {
			Statement bootstrap = conn.createStatement();
			bootstrap.execute("SET SCHEMA " + schema);
			bootstrap.close();
			try {
				for (int i = 0; i < prepared.size(); i++) {
					resp.getStatements().add(executeOne(conn, prepared.get(i), kinds.get(i), maxRows, i + 1));
				}
			}
			finally {
				Statement restore = conn.createStatement();
				restore.execute("SET SCHEMA PUBLIC");
				restore.close();
			}
			return null;
		});

		if (anyWrite) {
			schemaService.invalidate(sessionId);
		}
		resp.setElapsedMs(System.currentTimeMillis() - start);
		return resp;
	}

	private ExecuteResp.StmtResult executeOne(Connection conn, String sql, SqlSafetyGuard.Kind kind, int maxRows,
			int index) throws SQLException {
		ExecuteResp.StmtResult r = new ExecuteResp.StmtResult();
		r.setIndex(index);
		r.setSql(sql);
		r.setKind(kind.name());
		long t0 = System.currentTimeMillis();
		try (Statement stmt = conn.createStatement()) {
			stmt.setQueryTimeout(Math.max(1, properties.getQueryTimeoutMs() / 1000));
			if (kind == SqlSafetyGuard.Kind.SELECT) {
				stmt.setMaxRows(maxRows + 1);
				try (ResultSet rs = stmt.executeQuery(sql)) {
					ResultSetMetaData md = rs.getMetaData();
					int cols = md.getColumnCount();
					for (int c = 1; c <= cols; c++) {
						String label = md.getColumnLabel(c);
						r.getColumns().add(new ExecuteResp.ColumnMeta(md.getColumnName(c), label,
								md.getColumnTypeName(c)));
					}
					long cells = 0;
					while (rs.next()) {
						if (r.getRows().size() >= maxRows) {
							r.setTruncated(true);
							break;
						}
						List<Object> row = new ArrayList<>(cols);
						for (int c = 1; c <= cols; c++) {
							row.add(convert(rs.getObject(c)));
							cells++;
						}
						r.getRows().add(row);
						if (cells > properties.getMaxCells()) {
							r.setTruncated(true);
							break;
						}
					}
					r.setRowCount(r.getRows().size());
				}
			}
			else {
				int affected = stmt.executeUpdate(sql);
				r.setAffectedRows(affected);
			}
		}
		catch (SQLException e) {
			throw e;
		}
		r.setElapsedMs(System.currentTimeMillis() - t0);
		return r;
	}

	public static Object convert(Object v) {
		if (v == null) {
			return null;
		}
		if (v instanceof Timestamp ts) {
			LocalDateTime dt = ts.toLocalDateTime();
			return dt.format(TS);
		}
		if (v instanceof java.sql.Date d) {
			return d.toLocalDate().toString();
		}
		if (v instanceof java.sql.Time t) {
			return t.toLocalTime().toString();
		}
		if (v instanceof byte[] bytes) {
			StringBuilder sb = new StringBuilder("0x");
			for (byte b : bytes) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		}
		if (v instanceof Number || v instanceof Boolean || v instanceof String) {
			return v;
		}
		return String.valueOf(v);
	}

	public static class GuardException extends RuntimeException {

		public GuardException(String message) {
			super(message);
		}
	}

	static String lower(String s) {
		return s == null ? null : s.toLowerCase(Locale.ROOT);
	}
}
