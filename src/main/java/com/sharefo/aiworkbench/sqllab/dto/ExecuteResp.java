package com.sharefo.aiworkbench.sqllab.dto;

import java.util.ArrayList;
import java.util.List;

public class ExecuteResp {

	private long elapsedMs;

	private boolean truncated;

	private boolean needConfirm;

	private String confirmMessage;

	private String message;

	private List<StmtResult> statements = new ArrayList<>();

	public long getElapsedMs() {
		return elapsedMs;
	}

	public void setElapsedMs(long elapsedMs) {
		this.elapsedMs = elapsedMs;
	}

	public boolean isTruncated() {
		return truncated;
	}

	public void setTruncated(boolean truncated) {
		this.truncated = truncated;
	}

	public boolean isNeedConfirm() {
		return needConfirm;
	}

	public void setNeedConfirm(boolean needConfirm) {
		this.needConfirm = needConfirm;
	}

	public String getConfirmMessage() {
		return confirmMessage;
	}

	public void setConfirmMessage(String confirmMessage) {
		this.confirmMessage = confirmMessage;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public List<StmtResult> getStatements() {
		return statements;
	}

	public void setStatements(List<StmtResult> statements) {
		this.statements = statements;
	}

	public static class StmtResult {

		private int index;

		private String sql;

		private String kind;

		private List<ColumnMeta> columns = new ArrayList<>();

		private List<List<Object>> rows = new ArrayList<>();

		private int rowCount;

		private Integer affectedRows;

		private long elapsedMs;

		private boolean truncated;

		private String note;

		public int getIndex() {
			return index;
		}

		public void setIndex(int index) {
			this.index = index;
		}

		public String getSql() {
			return sql;
		}

		public void setSql(String sql) {
			this.sql = sql;
		}

		public String getKind() {
			return kind;
		}

		public void setKind(String kind) {
			this.kind = kind;
		}

		public List<ColumnMeta> getColumns() {
			return columns;
		}

		public void setColumns(List<ColumnMeta> columns) {
			this.columns = columns;
		}

		public List<List<Object>> getRows() {
			return rows;
		}

		public void setRows(List<List<Object>> rows) {
			this.rows = rows;
		}

		public int getRowCount() {
			return rowCount;
		}

		public void setRowCount(int rowCount) {
			this.rowCount = rowCount;
		}

		public Integer getAffectedRows() {
			return affectedRows;
		}

		public void setAffectedRows(Integer affectedRows) {
			this.affectedRows = affectedRows;
		}

		public long getElapsedMs() {
			return elapsedMs;
		}

		public void setElapsedMs(long elapsedMs) {
			this.elapsedMs = elapsedMs;
		}

		public boolean isTruncated() {
			return truncated;
		}

		public void setTruncated(boolean truncated) {
			this.truncated = truncated;
		}

		public String getNote() {
			return note;
		}

		public void setNote(String note) {
			this.note = note;
		}
	}

	public static class ColumnMeta {

		private String name;

		private String label;

		private String type;

		public ColumnMeta() {
		}

		public ColumnMeta(String name, String label, String type) {
			this.name = name;
			this.label = label;
			this.type = type;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getLabel() {
			return label;
		}

		public void setLabel(String label) {
			this.label = label;
		}

		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}
	}
}
