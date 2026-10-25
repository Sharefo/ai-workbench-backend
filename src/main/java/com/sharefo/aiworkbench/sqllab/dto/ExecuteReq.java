package com.sharefo.aiworkbench.sqllab.dto;

public class ExecuteReq {

	private String sql;

	/** QUERY 或 ADMIN */
	private String mode = "QUERY";

	private Integer maxRows;

	/** 危险操作二次确认后由前端回传 true */
	private boolean confirmed = false;

	public String getSql() {
		return sql;
	}

	public void setSql(String sql) {
		this.sql = sql;
	}

	public String getMode() {
		return mode;
	}

	public void setMode(String mode) {
		this.mode = mode;
	}

	public Integer getMaxRows() {
		return maxRows;
	}

	public void setMaxRows(Integer maxRows) {
		this.maxRows = maxRows;
	}

	public boolean isConfirmed() {
		return confirmed;
	}

	public void setConfirmed(boolean confirmed) {
		this.confirmed = confirmed;
	}
}
