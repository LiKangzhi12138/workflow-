package com.workflow.exception;

public class DatabaseSchemaMismatchException extends BusinessException {

    public DatabaseSchemaMismatchException(String message) {
        super("DB_SCHEMA_MISMATCH", message);
    }

    public DatabaseSchemaMismatchException(String message, Throwable cause) {
        super("DB_SCHEMA_MISMATCH", message, cause);
    }
}
