// com.defistat.web3.RetryableRpcException.java
package com.defistat.web3.exception;

/** Marker exception indicating the call can be retried on a different RPC endpoint. */
public class RetryableRpcException extends RuntimeException {
    public RetryableRpcException(String message) { super(message); }
    public RetryableRpcException(String message, Throwable cause) { super(message, cause); }
}