package com.sharefo.aiworkbench.sqllab.dto;

import java.util.ArrayList;
import java.util.List;

public class SchemaResp {

	private String dialect = "H2 / MySQL";

	private String sessionId;

	private List<TableMeta> tables = new ArrayList<>();

	public String getDialect() {
		return dialect;
	}

	public void setDialect(String dialect) {
		this.dialect = dialect;
	}

	public String getSessionId() {
		return sessionId;
	}

	public void setSessionId(String sessionId) {
		this.sessionId = sessionId;
	}

	public List<TableMeta> getTables() {
		return tables;
	}

	public void setTables(List<TableMeta> tables) {
		this.tables = tables;
	}

	public static class TableMeta {

		private String name;

		private String comment;

		private long rowCount;

		private List<ColumnInfo> columns = new ArrayList<>();

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getComment() {
			return comment;
		}

		public void setComment(String comment) {
			this.comment = comment;
		}

		public long getRowCount() {
			return rowCount;
		}

		public void setRowCount(long rowCount) {
			this.rowCount = rowCount;
		}

		public List<ColumnInfo> getColumns() {
			return columns;
		}

		public void setColumns(List<ColumnInfo> columns) {
			this.columns = columns;
		}
	}

	public static class ColumnInfo {

		private String name;

		private String type;

		private boolean nullable;

		private boolean primaryKey;

		private String comment;

		public ColumnInfo() {
		}

		public ColumnInfo(String name, String type, boolean nullable, boolean primaryKey, String comment) {
			this.name = name;
			this.type = type;
			this.nullable = nullable;
			this.primaryKey = primaryKey;
			this.comment = comment;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}

		public boolean isNullable() {
			return nullable;
		}

		public void setNullable(boolean nullable) {
			this.nullable = nullable;
		}

		public boolean isPrimaryKey() {
			return primaryKey;
		}

		public void setPrimaryKey(boolean primaryKey) {
			this.primaryKey = primaryKey;
		}

		public String getComment() {
			return comment;
		}

		public void setComment(String comment) {
			this.comment = comment;
		}
	}
}
