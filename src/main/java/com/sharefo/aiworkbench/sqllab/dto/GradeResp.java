package com.sharefo.aiworkbench.sqllab.dto;

import java.util.ArrayList;
import java.util.List;

public class GradeResp {

	private boolean passed;

	private String status;

	private String message;

	private int expectedRowCount;

	private int actualRowCount;

	private List<String> hints = new ArrayList<>();

	public boolean isPassed() {
		return passed;
	}

	public void setPassed(boolean passed) {
		this.passed = passed;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public int getExpectedRowCount() {
		return expectedRowCount;
	}

	public void setExpectedRowCount(int expectedRowCount) {
		this.expectedRowCount = expectedRowCount;
	}

	public int getActualRowCount() {
		return actualRowCount;
	}

	public void setActualRowCount(int actualRowCount) {
		this.actualRowCount = actualRowCount;
	}

	public List<String> getHints() {
		return hints;
	}

	public void setHints(List<String> hints) {
		this.hints = hints;
	}
}
