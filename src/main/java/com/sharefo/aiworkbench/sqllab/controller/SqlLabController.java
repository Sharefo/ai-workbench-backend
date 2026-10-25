package com.sharefo.aiworkbench.sqllab.controller;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sharefo.aiworkbench.sqllab.config.LlmProperties;
import com.sharefo.aiworkbench.sqllab.config.SqlLabProperties;
import com.sharefo.aiworkbench.sqllab.dto.ApiResp;
import com.sharefo.aiworkbench.sqllab.dto.AskReq;
import com.sharefo.aiworkbench.sqllab.dto.ExecuteReq;
import com.sharefo.aiworkbench.sqllab.dto.ExecuteResp;
import com.sharefo.aiworkbench.sqllab.dto.ExerciseVO;
import com.sharefo.aiworkbench.sqllab.dto.GradeResp;
import com.sharefo.aiworkbench.sqllab.dto.SchemaResp;
import com.sharefo.aiworkbench.sqllab.dto.SeedPlan;
import com.sharefo.aiworkbench.sqllab.dto.SessionInfo;
import com.sharefo.aiworkbench.sqllab.exec.SqlErrorTranslator;
import com.sharefo.aiworkbench.sqllab.exec.SqlExecutor;
import com.sharefo.aiworkbench.sqllab.exercise.ExerciseService;
import com.sharefo.aiworkbench.sqllab.exercise.GradingService;
import com.sharefo.aiworkbench.sqllab.llm.LlmClient;
import com.sharefo.aiworkbench.sqllab.llm.LlmJsonExtractor;
import com.sharefo.aiworkbench.sqllab.llm.PromptBuilder;
import com.sharefo.aiworkbench.sqllab.meta.SchemaService;
import com.sharefo.aiworkbench.sqllab.mock.MockDataEngine;
import com.sharefo.aiworkbench.sqllab.session.SessionService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/sql")
public class SqlLabController {

	private static final String SESSION_HEADER = "X-Workbench-Session";

	private final SessionService sessions;

	private final SqlExecutor executor;

	private final SchemaService schemaService;

	private final MockDataEngine mockDataEngine;

	private final LlmClient llm;

	private final LlmProperties llmProperties;

	private final PromptBuilder prompts;

	private final ExerciseService exercises;

	private final GradingService grading;

	private final ObjectMapper mapper;

	private final SqlLabProperties properties;

	public SqlLabController(SessionService sessions, SqlExecutor executor, SchemaService schemaService,
			MockDataEngine mockDataEngine, LlmClient llm, LlmProperties llmProperties, PromptBuilder prompts,
			ExerciseService exercises, GradingService grading, ObjectMapper mapper, SqlLabProperties properties) {
		this.sessions = sessions;
		this.executor = executor;
		this.schemaService = schemaService;
		this.mockDataEngine = mockDataEngine;
		this.llm = llm;
		this.llmProperties = llmProperties;
		this.prompts = prompts;
		this.exercises = exercises;
		this.grading = grading;
		this.mapper = mapper;
		this.properties = properties;
	}

	@GetMapping("/schema")
	public ApiResp<SchemaResp> schema(HttpServletRequest request) {
		String sessionId = resolve(request);
		return ApiResp.ok(schemaService.load(sessionId, sessions.schemaOf(sessionId)));
	}

	@GetMapping("/sample")
	public ApiResp<Map<String, Object>> sample(HttpServletRequest request, @RequestParam String table,
			@RequestParam(defaultValue = "5") int n) {
		String sessionId = resolve(request);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("table", table);
		data.put("rows", schemaService.sample(sessions.schemaOf(sessionId), table, n));
		return ApiResp.ok(data);
	}

	@PostMapping("/execute")
	public ApiResp<ExecuteResp> execute(HttpServletRequest request, @RequestBody ExecuteReq req) {
		String sessionId = resolve(request);
		try {
			return ApiResp.ok(executor.run(sessionId, sessions.schemaOf(sessionId), req));
		}
		catch (SqlExecutor.GuardException e) {
			return ApiResp.fail("GUARD", e.getMessage());
		}
		catch (Exception e) {
			return ApiResp.fail("SQL_ERROR", SqlErrorTranslator.translate(e.getMessage()));
		}
	}

	@GetMapping("/sessions")
	public ApiResp<List<SessionInfo>> sessions() {
		List<SessionInfo> list = sessions.list();
		for (SessionInfo si : list) {
			si.setTableCount(sessions.tableCount(si.getId()));
		}
		return ApiResp.ok(list);
	}

	@PostMapping("/sessions")
	public ApiResp<Map<String, Object>> createSession(@RequestParam(required = false) String name) {
		String id = sessions.create(name);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("id", id);
		data.put("name", name == null || name.isBlank() ? "未命名空间" : name);
		return ApiResp.ok(data);
	}

	@DeleteMapping("/sessions/{id}")
	public ApiResp<Boolean> deleteSession(@PathVariable String id) {
		sessions.delete(id);
		return ApiResp.ok(true);
	}

	@PostMapping("/dataset/reset")
	public ApiResp<Map<String, Object>> reset(HttpServletRequest request) {
		String sessionId = resolve(request);
		long t0 = System.currentTimeMillis();
		sessions.reset(sessionId);
		schemaService.invalidate(sessionId);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("sessionId", sessionId);
		data.put("elapsedMs", System.currentTimeMillis() - t0);
		return ApiResp.ok(data);
	}

	@PostMapping("/ai/seed-plan")
	public ApiResp<SeedPlan> seedPlan(HttpServletRequest request, @RequestBody AskReq req) {
		String sessionId = resolve(request);
		SchemaResp schema = schemaService.load(sessionId, sessions.schemaOf(sessionId));
		String schemaText = prompts.schemaText(schema, req.getInstruction());
		String raw;
		try {
			requireLlm();
			raw = llm.complete(prompts.seedPlanMessages(schemaText,
					req.getInstruction() == null ? "" : req.getInstruction(), req.getRowCount()), 0.2);
		}
		catch (Exception e) {
			return ApiResp.fail("LLM_ERROR", e.getMessage());
		}
		String json = LlmJsonExtractor.extractJson(raw);
		if (json.isBlank()) {
			return ApiResp.fail("LLM_PARSE", "模型没有返回可解析的造数方案，换个说法再试一次。原始输出：" + abbreviate(raw));
		}
		SeedPlan plan;
		try {
			plan = mapper.readValue(json, SeedPlan.class);
		}
		catch (Exception e) {
			return ApiResp.fail("LLM_PARSE", "造数方案格式有误：" + e.getMessage() + "｜原始输出：" + abbreviate(raw));
		}
		if (plan.getRowCount() <= 0) {
			plan.setRowCount(1000);
		}
		return ApiResp.ok(plan);
	}

	@PostMapping("/seed/apply")
	public ApiResp<Map<String, Object>> applySeed(HttpServletRequest request, @RequestBody SeedPlan plan) {
		String sessionId = resolve(request);
		try {
			long t0 = System.currentTimeMillis();
			MockDataEngine.ApplyResult result = mockDataEngine.apply(sessions.schemaOf(sessionId), plan);
			schemaService.invalidate(sessionId);
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("table", plan.getTable());
			data.put("ddl", result.getDdl());
			data.put("inserted", result.getInserted());
			data.put("elapsedMs", System.currentTimeMillis() - t0);
			return ApiResp.ok(data);
		}
		catch (IllegalArgumentException e) {
			return ApiResp.fail("PLAN_INVALID", e.getMessage());
		}
		catch (Exception e) {
			return ApiResp.fail("SEED_ERROR", SqlErrorTranslator.translate(e.getMessage()));
		}
	}

	@PostMapping("/ai/generate")
	public void generate(HttpServletRequest request, HttpServletResponse response, @RequestBody AskReq req)
			throws IOException {
		String sessionId = resolve(request);
		SchemaResp schema = schemaService.load(sessionId, sessions.schemaOf(sessionId));
		stream(response, prompts.generateMessages(prompts.schemaText(schema, req.getQuestion()),
				req.getQuestion() == null ? "" : req.getQuestion()), llmProperties.getTemperature(), true);
	}

	@PostMapping("/ai/fix")
	public void fix(HttpServletRequest request, HttpServletResponse response, @RequestBody AskReq req)
			throws IOException {
		String sessionId = resolve(request);
		SchemaResp schema = schemaService.load(sessionId, sessions.schemaOf(sessionId));
		stream(response, prompts.fixMessages(prompts.schemaText(schema, req.getSql()),
				req.getSql() == null ? "" : req.getSql(), req.getErrorMessage() == null ? "" : req.getErrorMessage()),
				llmProperties.getTemperature(), true);
	}

	@PostMapping("/ai/explain")
	public void explain(HttpServletRequest request, HttpServletResponse response, @RequestBody AskReq req)
			throws IOException {
		resolve(request);
		stream(response, prompts.explainMessages(req.getSql() == null ? "" : req.getSql()), 0.3, false);
	}

	@GetMapping("/exercises")
	public ApiResp<List<ExerciseVO>> exercises() {
		return ApiResp.ok(exercises.list());
	}

	@PostMapping("/exercises/grade")
	public ApiResp<GradeResp> grade(HttpServletRequest request, @RequestBody Map<String, String> body) {
		String sessionId = resolve(request);
		String exerciseId = body.get("exerciseId");
		String sql = body.get("sql");
		try {
			return ApiResp.ok(grading.grade(sessionId, sessions.schemaOf(sessionId), exerciseId, sql));
		}
		catch (Exception e) {
			GradeResp r = new GradeResp();
			r.setStatus("ERROR");
			r.setMessage(e.getMessage());
			return ApiResp.ok(r);
		}
	}

	@GetMapping("/config")
	public ApiResp<Map<String, Object>> config() {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("llmEnabled", llmProperties.isAvailable());
		data.put("model", llmProperties.getModel());
		data.put("maxRows", properties.getMaxRows());
		data.put("hardMaxRows", properties.getHardMaxRows());
		data.put("adminEnabled", properties.isAdminEnabled());
		data.put("scale", properties.getSeed().getScale());
		return ApiResp.ok(data);
	}

	private String resolve(HttpServletRequest request) {
		String header = request.getHeader(SESSION_HEADER);
		String id = sessions.ensure(header, header == null || header.isBlank() ? "默认空间" : "我的空间");
		sessions.ensureReady(id);
		return id;
	}

	private void requireLlm() {
		if (!llmProperties.isAvailable()) {
			throw new IllegalStateException("还没有配置可用的大模型：请在环境变量 LLM_API_KEY 或 application-local.yml 中填写密钥");
		}
	}

	private void stream(HttpServletResponse response, List<Map<String, Object>> messages, double temperature,
			boolean extractSql) throws IOException {
		response.setStatus(200);
		response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader("X-Accel-Buffering", "no");
		StringBuilder full = new StringBuilder();
		try {
			if (!llmProperties.isAvailable()) {
				throw new IllegalStateException(
						"还没有配置可用的大模型：请在环境变量 LLM_API_KEY 或 application-local.yml 中填写密钥");
			}
			llm.stream(messages, temperature, delta -> {
				full.append(delta);
				write(response, mapper.writeValueAsString(Map.of("type", "delta", "text", delta)));
			});
			Map<String, Object> done = new LinkedHashMap<>();
			done.put("type", "done");
			done.put("text", full.toString());
			if (extractSql) {
				done.put("sql", LlmJsonExtractor.extractSql(full.toString()));
				done.put("explanation", LlmJsonExtractor.extractExplanation(full.toString()));
			}
			write(response, mapper.writeValueAsString(done));
		}
		catch (Exception e) {
			String message = e instanceof IllegalStateException ? e.getMessage()
					: "调用模型失败：" + e.getMessage();
			write(response, mapper.writeValueAsString(Map.of("type", "error", "message", message)));
		}
	}

	private void write(HttpServletResponse response, String data) {
		try {
			response.getWriter().write("data: " + data + "\n\n");
			response.getWriter().flush();
		}
		catch (IOException ignored) {
			// 客户端断开连接
		}
	}

	private static String abbreviate(String s) {
		if (s == null) {
			return "";
		}
		return s.length() > 300 ? s.substring(0, 300) + "…" : s;
	}
}
