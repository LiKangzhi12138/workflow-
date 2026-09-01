package com.workflow.controller;

import com.workflow.common.ApiResponse;
import com.workflow.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<?> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("Business exception: path={}, code={}, message={}", request.getRequestURI(), e.getCode(), e.getMessage());
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ApiResponse<?> handleValidationException(Exception e, HttpServletRequest request) {
        String message = resolveValidationMessage(e);
        log.warn("Validation exception: path={}, message={}", request.getRequestURI(), message);
        return ApiResponse.error("PARAM_ERROR", message);
    }

    @ExceptionHandler({BadSqlGrammarException.class, DataAccessException.class, SQLException.class})
    public ApiResponse<?> handleDatabaseException(Exception e) {
        Throwable rootCause = getRootCause(e);
        String rootMessage = rootCause.getMessage() == null ? "" : rootCause.getMessage();

        if (rootMessage.contains("Unknown column")) {
            log.error("Database column mismatch detected", e);
            return ApiResponse.error(
                    "DB_SCHEMA_MISMATCH",
                    "数据库字段缺失，请检查 workflow_model_upload 表结构是否已同步"
            );
        }

        log.error("Database access error", e);
        return ApiResponse.error("DB_ERROR", "数据库操作失败，请稍后重试");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<?> handleIllegalArgumentException(IllegalArgumentException e) {
        return ApiResponse.error("PARAM_ERROR", e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ApiResponse<?> handleRuntimeException(RuntimeException e) {
        log.error("Unhandled runtime exception", e);
        return ApiResponse.error("BIZ_ERROR", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<?> handleException(Exception e) {
        log.error("Unhandled system exception", e);
        return ApiResponse.error("SYSTEM_ERROR", "系统异常，请稍后重试");
    }

    private String resolveValidationMessage(Exception e) {
        if (e instanceof MethodArgumentNotValidException) {
            MethodArgumentNotValidException exception = (MethodArgumentNotValidException) e;
            return exception.getBindingResult().getFieldError() != null
                    ? exception.getBindingResult().getFieldError().getDefaultMessage()
                    : "参数校验失败";
        }
        if (e instanceof BindException) {
            BindException exception = (BindException) e;
            return exception.getBindingResult().getFieldError() != null
                    ? exception.getBindingResult().getFieldError().getDefaultMessage()
                    : "参数校验失败";
        }
        if (e instanceof ConstraintViolationException) {
            ConstraintViolationException exception = (ConstraintViolationException) e;
            if (!exception.getConstraintViolations().isEmpty()) {
                return exception.getConstraintViolations().iterator().next().getMessage();
            }
        }
        if (e instanceof MethodArgumentTypeMismatchException) {
            MethodArgumentTypeMismatchException exception = (MethodArgumentTypeMismatchException) e;
            return exception.getName() + " 参数类型不正确";
        }
        if (e instanceof HttpMessageNotReadableException) {
            return "请求体格式不正确";
        }
        return "参数校验失败";
    }

    private Throwable getRootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
