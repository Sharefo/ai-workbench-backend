package com.sharefo.aiworkbench.sqllab.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SeedPlan {

	private String table;

	private String comment;

	private int rowCount = 1000;

	private boolean dropIfExists = false;

	private List<SeedColumn> columns = new ArrayList<>();

	public String getTable() {
		return table;
	}

	public void setTable(String table) {
		this.table = table;
	}

	public String getComment() {
		return comment;
	}

	public void setComment(String comment) {
		this.comment = comment;
	}

	public int getRowCount() {
		return rowCount;
	}

	public void setRowCount(int rowCount) {
		this.rowCount = rowCount;
	}

	public boolean isDropIfExists() {
		return dropIfExists;
	}

	public void setDropIfExists(boolean dropIfExists) {
		this.dropIfExists = dropIfExists;
	}

	public List<SeedColumn> getColumns() {
		return columns;
	}

	public void setColumns(List<SeedColumn> columns) {
		this.columns = columns;
	}

	public static class SeedColumn {

		private String name;

		private String type;

		private String comment;

		private boolean primaryKey;

		/** 生成规则，键：kind 及各自参数，详见 ValueGenerators */
		private Map<String, Object> rule;

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

		public String getComment() {
			return comment;
		}

		public void setComment(String comment) {
			this.comment = comment;
		}

		public boolean isPrimaryKey() {
			return primaryKey;
		}

		public void setPrimaryKey(boolean primaryKey) {
			this.primaryKey = primaryKey;
		}

		public Map<String, Object> getRule() {
			return rule;
		}

		public void setRule(Map<String, Object> rule) {
			this.rule = rule;
		}
	}
}
