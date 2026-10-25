package com.sharefo.aiworkbench.sqllab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("sqllab")
public class SqlLabProperties {

	private int maxRows = 1000;

	private int hardMaxRows = 5000;

	private int maxCells = 100000;

	private int queryTimeoutMs = 10000;

	private boolean adminEnabled = true;

	private Seed seed = new Seed();

	public int getMaxRows() {
		return maxRows;
	}

	public void setMaxRows(int maxRows) {
		this.maxRows = maxRows;
	}

	public int getHardMaxRows() {
		return hardMaxRows;
	}

	public void setHardMaxRows(int hardMaxRows) {
		this.hardMaxRows = hardMaxRows;
	}

	public int getMaxCells() {
		return maxCells;
	}

	public void setMaxCells(int maxCells) {
		this.maxCells = maxCells;
	}

	public int getQueryTimeoutMs() {
		return queryTimeoutMs;
	}

	public void setQueryTimeoutMs(int queryTimeoutMs) {
		this.queryTimeoutMs = queryTimeoutMs;
	}

	public boolean isAdminEnabled() {
		return adminEnabled;
	}

	public void setAdminEnabled(boolean adminEnabled) {
		this.adminEnabled = adminEnabled;
	}

	public Seed getSeed() {
		return seed;
	}

	public void setSeed(Seed seed) {
		this.seed = seed;
	}

	public static class Seed {

		private String scale = "small";

		public String getScale() {
			return scale;
		}

		public void setScale(String scale) {
			this.scale = scale;
		}
	}
}
