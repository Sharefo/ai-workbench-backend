package com.sharefo.aiworkbench.sqllab.session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;

import com.sharefo.aiworkbench.sqllab.config.SqlLabProperties;
import com.sharefo.aiworkbench.sqllab.dataset.EcommerceDataset;
import com.sharefo.aiworkbench.sqllab.dto.SessionInfo;

/**
 * 多用户隔离：每个会话独占一个 H2 schema（s_&lt;id&gt;），互不可见。
 */
@Service
public class SessionService {

	private final JdbcTemplate jdbc;

	private final EcommerceDataset dataset;

	private final SqlLabProperties properties;

	private final Map<String, Object> locks = new ConcurrentHashMap<>();

	public SessionService(DataSource dataSource, EcommerceDataset dataset, SqlLabProperties properties) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.dataset = dataset;
		this.properties = properties;
		initMetadata();
	}

	public void initMetadata() {
		jdbc.execute("CREATE TABLE IF NOT EXISTS workbench_session ("
				+ "id VARCHAR(32) NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL, scale VARCHAR(16) NOT NULL,"
				+ " created_at TIMESTAMP NOT NULL, last_active_at TIMESTAMP NOT NULL, ready BOOLEAN NOT NULL)");
		ensure("default", "默认空间");
	}

	public String schemaOf(String sessionId) {
		return "s_" + sessionId.toLowerCase(Locale.ROOT);
	}

	public List<SessionInfo> list() {
		List<Map<String, Object>> rows =
				jdbc.queryForList("SELECT id, name, scale, created_at, last_active_at, ready "
						+ "FROM workbench_session ORDER BY last_active_at DESC");
		List<SessionInfo> out = new ArrayList<>();
		for (Map<String, Object> r : rows) {
			SessionInfo si = new SessionInfo(str(r.get("ID")), str(r.get("NAME")), str(r.get("SCALE")),
					str(r.get("CREATED_AT")), str(r.get("LAST_ACTIVE_AT")), bool(r.get("READY")));
			out.add(si);
		}
		return out;
	}

	public String create(String name) {
		String id = "u" + Long.toHexString(System.nanoTime() ^ System.currentTimeMillis()).substring(0, 8);
		jdbc.update("INSERT INTO workbench_session (id, name, scale, created_at, last_active_at, ready)"
						+ " VALUES (?, ?, ?, ?, ?, ?)",
				id, name == null || name.isBlank() ? "未命名空间" : name, properties.getSeed().getScale(),
				LocalDateTime.now(), LocalDateTime.now(), false);
		return id;
	}

	public String ensure(String sessionId, String fallbackName) {
		String id = normalize(sessionId);
		Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM workbench_session WHERE id = ?", Integer.class, id);
		if (cnt == null || cnt == 0) {
			jdbc.update("INSERT INTO workbench_session (id, name, scale, created_at, last_active_at, ready)"
							+ " VALUES (?, ?, ?, ?, ?, ?)",
					id, fallbackName, properties.getSeed().getScale(), LocalDateTime.now(), LocalDateTime.now(), false);
		}
		return id;
	}

	public void touch(String sessionId) {
		jdbc.update("UPDATE workbench_session SET last_active_at = ? WHERE id = ?", LocalDateTime.now(), sessionId);
	}

	/** 首次访问时建 schema 并灌入内置数据集 */
	public void ensureReady(String sessionId) {
		Boolean ready = jdbc.queryForObject("SELECT ready FROM workbench_session WHERE id = ?", Boolean.class,
				sessionId);
		if (Boolean.TRUE.equals(ready)) {
			return;
		}
		synchronized (locks.computeIfAbsent(sessionId, k -> new Object())) {
			ready = jdbc.queryForObject("SELECT ready FROM workbench_session WHERE id = ?", Boolean.class, sessionId);
			if (Boolean.TRUE.equals(ready)) {
				return;
			}
			String schema = schemaOf(sessionId);
			final javax.sql.DataSource ds = jdbc.getDataSource();
			Connection conn = DataSourceUtils.getConnection(ds);
			try {
				conn.setAutoCommit(false);
				try (Statement st = conn.createStatement()) {
					st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
					st.execute("CREATE SCHEMA " + schema);
					st.execute("SET SCHEMA " + schema);
					dataset.seed(conn, properties.getSeed().getScale());
					st.execute("SET SCHEMA PUBLIC");
				}
				conn.commit();
			}
			catch (Exception e) {
				try {
					conn.rollback();
				}
				catch (SQLException ignored) {
					// ignore
				}
				throw new IllegalStateException("初始化内置数据集失败：" + e.getMessage(), e);
			}
			finally {
				try {
					conn.setAutoCommit(true);
				}
				catch (SQLException ignored) {
					// ignore
				}
				DataSourceUtils.releaseConnection(conn, ds);
			}
			jdbc.update("UPDATE workbench_session SET ready = ? WHERE id = ?", true, sessionId);
		}
	}

	/** 丢弃空间内所有表并重新灌入内置数据集 */
	public void reset(String sessionId) {
		jdbc.update("UPDATE workbench_session SET ready = ? WHERE id = ?", false, sessionId);
		ensureReady(sessionId);
	}

	public void delete(String sessionId) {
		if ("default".equals(sessionId)) {
			reset(sessionId);
			return;
		}
		jdbc.update("DELETE FROM workbench_session WHERE id = ?", sessionId);
		jdbc.execute("DROP SCHEMA IF EXISTS " + schemaOf(sessionId) + " CASCADE");
	}

	public int tableCount(String sessionId) {
		Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ?"
						+ " AND TABLE_TYPE IN ('BASE TABLE', 'TABLE')",
				Integer.class, schemaOf(sessionId).toUpperCase(Locale.ROOT));
		return n == null ? 0 : n;
	}

	private String normalize(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			return "default";
		}
		String s = sessionId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
		return s.isBlank() ? "default" : s.substring(0, Math.min(32, s.length()));
	}

	private static String str(Object o) {
		return o == null ? "" : String.valueOf(o);
	}

	private static boolean bool(Object o) {
		return Boolean.TRUE.equals(o) || "1".equals(String.valueOf(o)) || "true".equalsIgnoreCase(String.valueOf(o));
	}
}
