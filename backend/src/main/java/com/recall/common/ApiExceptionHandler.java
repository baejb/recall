package com.recall.common;

import com.recall.settings.EmbeddingProbeException;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 전역 예외 핸들러 — 컨트롤러마다 try-catch를 흩뿌리지 않고 예외를 한 곳에서 HTTP 응답으로 변환한다(CLAUDE.md 규칙). 응답 본문은 표준 {@link
 * ProblemDetail}(RFC 7807)로 통일한다.
 *
 * <p>{@link ResponseEntityExceptionHandler}를 상속해 잘못된 JSON·미지원 메서드 등 Spring MVC 표준 예외는 부모가 올바른 4xx로
 * 처리하게 두고(그대로 500으로 삼키지 않도록), 여기서는 도메인 예외만 얹는다. 조용한 실패 금지: 서버 잘못(5xx)은 로그로 드러낸다.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 요청 바디 검증 실패(@Valid) — 부모의 400 처리에 필드별 메시지를 실어준다. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<String> errors =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                        .toList();
        ProblemDetail body =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "요청 값이 유효하지 않습니다.");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /** 파라미터/경로 변수 검증 실패 — 400. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraint(ConstraintViolationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** 잘못된 인자(예: 등록된 전략이 없는 유형) — 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** 임베딩 설정 저장 전 프로브 실패(키/모델 오류 등) — 400. 키 값은 메시지에 담기지 않는다. */
    @ExceptionHandler(EmbeddingProbeException.class)
    public ProblemDetail handleEmbeddingProbe(EmbeddingProbeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** 아직 구현되지 않은 stub 단계 — 501. Phase 1에서 실제 구현으로 대체된다. */
    @ExceptionHandler(UnsupportedOperationException.class)
    public ProblemDetail handleNotImplemented(UnsupportedOperationException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, ex.getMessage());
    }

    /** 그 외 예상 못 한 예외 — 500. 삼키지 않고 로그로 드러낸다(조용한 실패 금지). */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("처리되지 않은 예외", ex);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");
    }
}
