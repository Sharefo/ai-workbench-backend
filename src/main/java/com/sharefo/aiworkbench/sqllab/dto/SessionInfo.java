package com.sharefo.aiworkbench.sqllab.dto;

public class SessionInfo {

	private String id;

	private String name;

	private String scale;

	private String createdAt;

	private String lastActiveAt;

	private boolean ready;

	private int tableCount;

	public SessionInfo() {
	}

	public SessionInfo(String id, String name, String scale, String createdAt, String lastActiveAt, boolean ready) {
		this.id = id;
		this.name = name;
		this.scale = scale;
		this.createdAt = createdAt;
		this.lastActiveAt = lastActiveAt;
		this.ready = ready;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getScale() {
		return scale;
	}

	public void setScale(String scale) {
		this.scale = scale;
	}

	public String getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(String createdAt) {
		this.createdAt = createdAt;
	}

	public String getLastActiveAt() {
		return lastActiveAt;
	}

	public void setLastActiveAt(String lastActiveAt) {
		this.lastActiveAt = lastActiveAt;
	}

	public boolean isReady() {
		return ready;
	}

	public void setReady(boolean ready) {
		this.ready = ready;
	}

	public int getTableCount() {
		return tableCount;
	}

	public void setTableCount(int tableCount) {
		this.tableCount = tableCount;
	}
}
