package com.qaqxianyuqaq.backend.exception;

import com.qaqxianyuqaq.backend.domain.vo.common.ErrorVO;
import com.qaqxianyuqaq.backend.domain.vo.common.FieldErrorVO;

import com.qaqxianyuqaq.backend.util.RequestUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 全局异常处理器。
 *
 * <p>所有异常统一转换为 {error:{code,message,details},requestId} 结构，避免泄漏堆栈信息。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 处理业务异常。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorVO> handleBusiness(BusinessException exception) {
        ErrorCode code = exception.errorCode();
        ErrorVO body = ErrorVO.of(code.code(), exception.getMessage(), exception.details(),
                RequestUtil.currentRequestId());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.status());
        if (exception instanceof RateLimitedException rateLimited) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(rateLimited.retryAfterSeconds()));
        }
        return builder.body(body);
    }

    /** 处理请求体字段校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorVO> handleValidation(MethodArgumentNotValidException exception) {
        List<FieldErrorVO> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorVO(error.getField(), error.getDefaultMessage()))
                .toList();
        return badRequest(details);
    }

    /** 处理查询参数校验失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorVO> handleConstraint(ConstraintViolationException exception) {
        List<FieldErrorVO> details = exception.getConstraintViolations().stream()
                .map(violation -> new FieldErrorVO(violation.getPropertyPath().toString(),
                        violation.getMessage()))
                .toList();
        return badRequest(details);
    }

    /** 处理请求参数缺失。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorVO> handleMissingParameter(MissingServletRequestParameterException exception) {
        return badRequest(List.of(new FieldErrorVO(exception.getParameterName(), "缺少必填参数")));
    }

    /** 处理请求体解析失败。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorVO> handleUnreadable(HttpMessageNotReadableException exception) {
        return badRequest(List.of(new FieldErrorVO("body", "请求体格式错误")));
    }

    /** 处理不支持的媒体类型。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorVO> handleMediaType(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(ErrorCode.UNSUPPORTED_MEDIA_TYPE.status())
                .body(ErrorVO.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE.code(), "请求需使用 application/json",
                        RequestUtil.currentRequestId()));
    }

    /** 处理上传体积超限。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorVO> handleMaxUpload(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(ErrorCode.PAYLOAD_TOO_LARGE.status())
                .body(ErrorVO.of(ErrorCode.PAYLOAD_TOO_LARGE.code(), "文件体积超过服务端限制",
                        RequestUtil.currentRequestId()));
    }

    /** 处理鉴权失败。 */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorVO> handleAuthentication(AuthenticationException exception) {
        return ResponseEntity.status(ErrorCode.INVALID_CREDENTIALS.status())
                .body(ErrorVO.of(ErrorCode.INVALID_CREDENTIALS.code(), ErrorCode.INVALID_CREDENTIALS.defaultMessage(),
                        RequestUtil.currentRequestId()));
    }

    /** 处理授权失败。 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorVO> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(ErrorCode.FORBIDDEN.status())
                .body(ErrorVO.of(ErrorCode.FORBIDDEN.code(), ErrorCode.FORBIDDEN.defaultMessage(),
                        RequestUtil.currentRequestId()));
    }

    /** 处理静态资源或路由不存在。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorVO> handleNoResource(NoResourceFoundException exception) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
                .body(ErrorVO.of(ErrorCode.NOT_FOUND.code(), "接口或资源不存在", RequestUtil.currentRequestId()));
    }

    /** 兜底处理未预期异常，仅记录服务端日志。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorVO> handleUnknown(Exception exception, HttpServletRequest request) {
        log.error("未处理异常, path={}", request.getRequestURI(), exception);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ErrorVO.of(ErrorCode.INTERNAL_ERROR.code(), ErrorCode.INTERNAL_ERROR.defaultMessage(),
                        RequestUtil.currentRequestId()));
    }

    /** 构造 400 字段校验错误响应。 */
    private ResponseEntity<ErrorVO> badRequest(List<FieldErrorVO> details) {
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
                .body(ErrorVO.of(ErrorCode.VALIDATION_ERROR.code(), ErrorCode.VALIDATION_ERROR.defaultMessage(),
                        details, RequestUtil.currentRequestId()));
    }
}
