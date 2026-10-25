package com.sharefo.aiworkbench.sqllab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.sharefo.aiworkbench.sqllab.dto.ExecuteReq;
import com.sharefo.aiworkbench.sqllab.dto.ExecuteResp;
import com.sharefo.aiworkbench.sqllab.dto.ExerciseVO;
import com.sharefo.aiworkbench.sqllab.dto.GradeResp;
import com.sharefo.aiworkbench.sqllab.dto.SchemaResp;
import com.sharefo.aiworkbench.sqllab.dto.SeedPlan;
import com.sharefo.aiworkbench.sqllab.exec.SqlExecutor;
import com.sharefo.aiworkbench.sqllab.exercise.ExerciseService;
import com.sharefo.aiworkbench.sqllab.exercise.GradingService;
import com.sharefo.aiworkbench.sqllab.meta.SchemaService;
import com.sharefo.aiworkbench.sqllab.mock.MockDataEngine;
import com.sharefo.aiworkbench.sqllab.session.SessionService;

@SpringBootTest
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sqllabtest;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"llm.enabled=false",
		"sqllab.seed.scale=small"
})
class SqlLabSmokeTests {

	@Autowired
	SessionService sessions;

	@Autowired
	SchemaService schemaService;

	@Autowired
	SqlExecutor executor;

	@Autowired
	ExerciseService exercises;

	@Autowired
	GradingService grading;

	@Autowired
	MockDataEngine mockDataEngine;

	private String sessionId;

	private String schema;

	@BeforeEach
	void setup() {
		sessionId = sessions.ensure("smoketest", "冒烟测试空间");
		schema = sessions.schemaOf(sessionId);
		sessions.ensureReady(sessionId);
	}

	@Test
	void 内置数据集应包含十张业务表() {
		SchemaResp resp = schemaService.load(sessionId, schema);
		List<String> names = resp.getTables().stream().map(SchemaResp.TableMeta::getName).toList();
		for (String expected : List.of("users", "products", "orders", "order_items", "payments", "campaigns",
				"categories", "dim_channel", "dim_date", "user_events")) {
			assertTrue(names.contains(expected), "缺少内置表 " + expected);
		}
		SchemaResp.TableMeta orders = resp.getTables().stream()
				.filter(t -> "orders".equals(t.getName())).findFirst().orElseThrow();
		assertTrue(orders.getRowCount() > 1000, "订单表应该有数据");
		assertTrue(orders.getColumns().stream().anyMatch(c -> "status".equals(c.getName())));
		assertTrue(orders.getColumns().stream().anyMatch(c -> c.isPrimaryKey()), "应该能识别出主键");
		assertEquals("订单主表", orders.getComment());
	}

	@Test
	void 查询模式应拦住写语句() {
		ExecuteReq req = new ExecuteReq();
		req.setSql("INSERT INTO orders (id, order_no, user_id, channel_id, status, amount, created_at)"
				+ " VALUES (9999999, 'x', 1, 1, '已完成', 1, NOW())");
		req.setMode("QUERY");
		assertThrows(SqlExecutor.GuardException.class, () -> executor.run(sessionId, schema, req));
	}

	@Test
	void 危险操作需要二次确认() {
		ExecuteReq req = new ExecuteReq();
		req.setSql("DROP TABLE orders");
		req.setMode("ADMIN");
		ExecuteResp resp = executor.run(sessionId, schema, req);
		assertTrue(resp.isNeedConfirm(), "DROP 必须先确认");
		req.setConfirmed(true);
		resp = executor.run(sessionId, schema, req);
		assertFalse(resp.isNeedConfirm());
		sessions.reset(sessionId);
		schemaService.invalidate(sessionId);
	}

	@Test
	void 便利命令可翻译成元数据查询() {
		ExecuteReq req = new ExecuteReq();
		req.setSql("SHOW TABLES");
		ExecuteResp resp = executor.run(sessionId, schema, req);
		assertEquals(1, resp.getStatements().size());
		assertTrue(resp.getStatements().get(0).getRowCount() >= 10);
	}

	@Test
	void 十五条练习题的参考答案都能跑通并自评通过() {
		for (ExerciseVO e : exercises.list()) {
			GradeResp r = grading.grade(sessionId, schema, e.getId(), e.getReferenceSql());
			assertTrue(r.isPassed(), "题目 " + e.getId() + " 未通过：" + r.getMessage() + " " + r.getHints());
		}
	}

	@Test
	void 错误答案应被判为不通过() {
		GradeResp r = grading.grade(sessionId, schema, "e04", "SELECT COUNT(*) FROM orders");
		assertFalse(r.isPassed());
		assertEquals("FAIL", r.getStatus());
		assertFalse(r.getHints().isEmpty(), "应给出差异提示");
	}

	@Test
	void 造数引擎能按规则建表灌数() {
		SeedPlan plan = new SeedPlan();
		plan.setTable("coupon_test");
		plan.setComment("优惠券测试表");
		plan.setRowCount(500);
		plan.setDropIfExists(true);
		plan.getColumns().add(col("id", "BIGINT", true, Map.of("kind", "sequence")));
		plan.getColumns().add(col("user_id", "BIGINT", false, Map.of("kind", "ref", "table", "users", "column", "id")));
		plan.getColumns().add(col("amount", "DECIMAL(10,2)", false,
				Map.of("kind", "decimal_range", "min", 5, "max", 200, "scale", 2)));
		plan.getColumns().add(col("status", "VARCHAR(16)", false,
				Map.of("kind", "enum", "values", List.of("未使用", "已使用", "已过期"), "weights", List.of(5, 3, 2))));
		plan.getColumns().add(col("get_date", "DATE", false,
				Map.of("kind", "date_range", "start", "2026-01-01", "end", "2026-06-30")));

		MockDataEngine.ApplyResult result = mockDataEngine.apply(schema, plan);
		assertEquals(500, result.getInserted());
		schemaService.invalidate(sessionId);

		ExecuteResp resp = executor.run(sessionId, schema, query("SELECT COUNT(*) AS c FROM coupon_test"));
		assertEquals(500L, resp.getStatements().get(0).getRows().get(0).get(0));

		resp = executor.run(sessionId, schema,
				query("SELECT COUNT(DISTINCT status) AS c FROM coupon_test"));
		assertEquals(3L, resp.getStatements().get(0).getRows().get(0).get(0));

		resp = executor.run(sessionId, schema,
				query("SELECT COUNT(*) AS c FROM coupon_test t JOIN users u ON t.user_id = u.id"));
		assertEquals(500L, resp.getStatements().get(0).getRows().get(0).get(0));
	}

	@Test
	void 非法造数方案应被拒绝() {
		SeedPlan plan = new SeedPlan();
		plan.setTable("bad table; DROP TABLE users");
		plan.setRowCount(10);
		plan.getColumns().add(col("id", "BIGINT", true, Map.of("kind", "sequence")));
		assertThrows(IllegalArgumentException.class, () -> mockDataEngine.apply(schema, plan));
	}

	private static ExecuteReq query(String sql) {
		ExecuteReq req = new ExecuteReq();
		req.setSql(sql);
		req.setMode("QUERY");
		req.setConfirmed(true);
		return req;
	}

	private static SeedPlan.SeedColumn col(String name, String type, boolean pk, Map<String, Object> rule) {
		SeedPlan.SeedColumn c = new SeedPlan.SeedColumn();
		c.setName(name);
		c.setType(type);
		c.setPrimaryKey(pk);
		c.setRule(rule);
		assertNotNull(c.getName());
		return c;
	}
}
