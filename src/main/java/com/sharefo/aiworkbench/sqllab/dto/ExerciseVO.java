package com.sharefo.aiworkbench.sqllab.dto;

import java.util.List;

public class ExerciseVO {

	private String id;

	private int level;

	private String title;

	private String description;

	private String hint;

	private String referenceSql;

	private boolean orderMatters;

	private List<String> tags;

	private String difficulty;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public int getLevel() {
		return level;
	}

	public void setLevel(int level) {
		this.level = level;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getHint() {
		return hint;
	}

	public void setHint(String hint) {
		this.hint = hint;
	}

	public String getReferenceSql() {
		return referenceSql;
	}

	public void setReferenceSql(String referenceSql) {
		this.referenceSql = referenceSql;
	}

	public boolean isOrderMatters() {
		return orderMatters;
	}

	public void setOrderMatters(boolean orderMatters) {
		this.orderMatters = orderMatters;
	}

	public List<String> getTags() {
		return tags;
	}

	public void setTags(List<String> tags) {
		this.tags = tags;
	}

	public String getDifficulty() {
		return difficulty;
	}

	public void setDifficulty(String difficulty) {
		this.difficulty = difficulty;
	}
}
