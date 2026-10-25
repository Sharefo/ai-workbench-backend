package com.sharefo.aiworkbench.sqllab.dto;

public class ApiResp<T> {

	private boolean ok;

	private T data;

	private Err error;

	public static <T> ApiResp<T> ok(T data) {
		ApiResp<T> r = new ApiResp<>();
		r.ok = true;
		r.data = data;
		return r;
	}

	public static <T> ApiResp<T> fail(String code, String message) {
		ApiResp<T> r = new ApiResp<>();
		r.ok = false;
		r.error = new Err(code, message);
		return r;
	}

	public boolean isOk() {
		return ok;
	}

	public void setOk(boolean ok) {
		this.ok = ok;
	}

	public T getData() {
		return data;
	}

	public void setData(T data) {
		this.data = data;
	}

	public Err getError() {
		return error;
	}

	public void setError(Err error) {
		this.error = error;
	}

	public static class Err {

		private String code;

		private String message;

		public Err() {
		}

		public Err(String code, String message) {
			this.code = code;
			this.message = message;
		}

		public String getCode() {
			return code;
		}

		public void setCode(String code) {
			this.code = code;
		}

		public String getMessage() {
			return message;
		}

		public void setMessage(String message) {
			this.message = message;
		}
	}
}
