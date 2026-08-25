package com.cursor.eclipse.acp;

/**
 * JSON-RPC error or transport failure from an ACP session.
 */
public class AcpException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final Integer code;

	public AcpException(String message) {
		super(message);
		this.code = null;
	}

	public AcpException(String message, Throwable cause) {
		super(message, cause);
		this.code = null;
	}

	public AcpException(int code, String message) {
		super(message);
		this.code = code;
	}

	public Integer getCode() {
		return code;
	}
}
