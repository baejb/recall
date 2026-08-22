package com.recall.common.exception;

import com.recall.common.web.ApiError;
import com.recall.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 예외 → 공통 응답 형식({@link ApiResponse#fail}) <b>단일 변환 지점</b>. 컨트롤러마다 try-catch 를 흩뿌리지 않는다.
 *
 * <p>상태 코드는 예외 타입이 아니라 {@link ErrorCode}에서 나온다 — 코드와 상태가 어긋나지 않는다.
 *
 * <p>모든 에러 응답은 {@code traceId}와 함께 서버 로그에 남긴다 — 사용자가 보고한 traceId 를 로그 라인과 상관시킬 수 있어야 한다. 추적할 수 없는
 * 식별자는 있으나 마나다.
 *
 * <p><b>catch-all 이 삼키던 것들을 개별 핸들러로 끌어냈다</b>(조용한 실패 금지): 타입 불일치·매핑 없는 경로·읽을 수 없는 본문·미지원 메서드는 모두
 * 호출자가 고칠 수 있는 4xx 인데, 핸들러가 없으면 맨 아래 {@code Exception} 핸들러가 붙잡아 <b>500</b>으로 나간다. 그러면 "서버가 고장났다"는
 * 잘못된 신호가 로그와 화면에 남는다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 도메인·애플리케이션 예외 — 예외가 지닌 ErrorCode 를 그대로 에러 응답에 싣는다. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApi(ApiException e) {
        return respond(e.code(), e.getMessage(), e.field());
    }

    /** 요청 바디 검증 실패(@Valid) — 400. 어느 필드인지 함께 싣는다(값이 아니라 이름). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleBodyValidation(
            MethodArgumentNotValidException e) {
        var fieldError = e.getBindingResult().getFieldError();
        String field = fieldError == null ? null : fieldError.getField();
        String message = fieldError == null ? "요청 값이 유효하지 않습니다" : fieldError.getDefaultMessage();

        return respond(ErrorCode.VALIDATION_ERROR, message, field);
    }

    /** 파라미터·경로 변수 제약 위반 — 400. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException e) {
        return respond(ErrorCode.VALIDATION_ERROR, "요청 값이 유효하지 않습니다", null);
    }

    /**
     * 본문을 읽을 수 없을 때(깨진 JSON) — 400.
     *
     * <p>파서 메시지를 그대로 내보내지 않는다: 내부 타입·클래스 이름이 응답으로 새고, 이 상황에서 호출자가 할 수 있는 일은 본문 형식을 고치는 것뿐이다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(
            HttpMessageNotReadableException e) {
        log.warn("요청 본문 파싱 실패: {}", e.getMessage());

        return respond(ErrorCode.VALIDATION_ERROR, "요청 본문을 읽을 수 없습니다", null);
    }

    /** 필수 요청 파라미터 누락 — 400. 어떤 파라미터가 빠졌는지는 알려 준다(이름이라 노출 위험이 없다). */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(
            MissingServletRequestParameterException e) {
        return respond(ErrorCode.VALIDATION_ERROR, "필수 요청 파라미터가 없습니다", e.getParameterName());
    }

    /**
     * 파라미터·경로 변수의 타입 불일치 — 400.
     *
     * <p>이 핸들러가 없으면 {@code /api/memories/abc} 나 {@code ?limit=많이} 같은 요청이 catch-all 을 타고 500 으로 나간다.
     * 기대 타입은 응답에 싣지 않는다 — 내부 클래스 이름이 새는 경로다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException e) {
        log.warn("요청 값 타입 불일치: name={}", e.getName());

        return respond(ErrorCode.VALIDATION_ERROR, "요청 값의 형식이 올바르지 않습니다", e.getName());
    }

    /**
     * 매핑되지 않은 경로 — 404.
     *
     * <p>없는 경로와 없는 리소스는 호출자에게 같은 사실이므로 같은 형식으로 답한다. 이 핸들러가 없으면 오타 난 URL 이 500 으로 나간다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("매핑 없는 경로: {}", e.getResourcePath());

        return respond(ErrorCode.NOT_FOUND, "해당 데이터 없음", null);
    }

    /**
     * 지원하지 않는 HTTP 메서드 — 405. 라우트는 있으나 그 동작이 없다는 사실을 그대로 알린다.
     *
     * <p>상태를 손으로 지정하지 않고 {@code respond()} 를 쓴다 — 전에는 `VALIDATION_ERROR`(선언 상태 400)를 실은 채 405 로 응답해
     * 코드와 상태가 어긋난 응답이 나갔고, 그건 이 클래스가 지킨다고 적어 둔 계약을 스스로 깬 것이었다.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {
        log.warn("미지원 메서드: {}", e.getMethod());

        return respond(ErrorCode.METHOD_NOT_ALLOWED, "지원하지 않는 메서드입니다", null);
    }

    /**
     * 지원하지 않는 요청 Content-Type — 415.
     *
     * <p>구 핸들러는 {@code ResponseEntityExceptionHandler} 를 상속해 스프링 MVC 표준 예외를 부모가 4xx 로 처리하게 뒀다. 상속을
     * 떼면서 그 커버리지가 사라져, JSON 엔드포인트에 {@code text/plain} 을 보내면 catch-all 이 붙잡아 <b>500</b>으로 나갔다 — 호출자가
     * 헤더만 고치면 되는 상황이 서버 장애로 보고됐다.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException e) {
        log.warn("미지원 Content-Type: {}", e.getContentType());

        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type 입니다", null);
    }

    /**
     * 클라이언트가 받을 수 없는 응답 타입 요청(Accept 헤더) — 406.
     *
     * <p>{@link HttpMediaTypeNotSupportedException} 과 같은 계열의 구멍이다: 둘 다 구 핸들러의 부모가 처리하던 표준 예외이고,
     * 핸들러가 없으면 catch-all 이 500 으로 바꾼다. 406 은 전용 코드를 만들지 않고 415 코드를 쓰지 않는다 — 미디어 타입 협상 실패라는 점은 같지만
     * 상태가 다르므로 코드도 달라야 한다는 이 열거의 규칙을 지켜, 상태와 짝이 맞는 {@code NOT_ACCEPTABLE} 을 쓴다.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotAcceptable(
            HttpMediaTypeNotAcceptableException e) {
        log.warn("수용 불가 Accept: {}", e.getMessage());

        return respond(ErrorCode.NOT_ACCEPTABLE, "요청한 응답 형식을 제공할 수 없습니다", null);
    }

    /**
     * 비동기 응답(SSE)의 타임아웃 — 503. 응답이 <b>이미 시작됐다면 아무것도 쓰지 않는다</b>.
     *
     * <p>{@link HttpMediaTypeNotSupportedException} 과 같은 계열의 구멍이었다(구 핸들러의 부모가 처리하던 표준 예외). 핸들러가 없으면
     * catch-all 이 붙잡아 예측 가능한 운영 상황(근거가 많거나 provider 가 느려 스트리밍이 60초를 넘김)이 "분류되지 않은 예외" ERROR 스택으로
     * 기록되고, 실제 결함이 그 소음에 묻힌다.
     *
     * <p>이 예외는 <b>대개 응답이 이미 시작된 뒤</b>에 온다(SSE 는 200 과 첫 조각을 먼저 보낸다). 그때 응답을 쓰지 않는 처리는 {@link
     * #envelope} 이 공통으로 담당한다 — 커밋 검사를 핸들러마다 손으로 넣으면 새 핸들러에서 빠뜨린다. 여기서는 <b>아직 아무것도 안 보낸</b> 타임아웃(근거
     * 검색 단계에서 초과)에 503 을 실어 주는 것이 역할이다.
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<ApiResponse<Void>> handleAsyncTimeout(AsyncRequestTimeoutException e) {
        return respond(ErrorCode.ASYNC_TIMEOUT, "응답 시간이 초과되었습니다 — 다시 시도해 주세요", null);
    }

    /**
     * 그 밖의 미분류 예외 — 500. 내부 정보는 응답에 싣지 않고 스택은 traceId 와 함께 로그로만 남긴다(조용한 실패 금지).
     *
     * <p>여기까지 온 예외는 <b>분류되지 않았다는 사실 자체가 결함</b>이다: 호출자가 고칠 수 있는 상황이면 위에 핸들러가 있어야 하고, 도메인 상황이면 {@link
     * ApiException} 하위 타입으로 던져야 한다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        ApiError error = ApiError.of(ErrorCode.INTERNAL_ERROR, "서버 내부 오류가 발생했습니다", null);
        log.error("분류되지 않은 예외 traceId={}", error.traceId(), e);

        return envelope(ErrorCode.INTERNAL_ERROR.status(), error);
    }

    private ResponseEntity<ApiResponse<Void>> respond(
            ErrorCode code, String message, String field) {
        ApiError error = ApiError.of(code, message, field);
        log.warn(
                "에러 응답 {} {} field={} traceId={}",
                code.status().value(),
                error.code(),
                error.field(),
                error.traceId());

        return envelope(code.status(), error);
    }

    /**
     * 에러 응답을 <b>Accept 헤더와 무관하게</b> JSON 으로 응답한다.
     *
     * <p><b>왜 Content-Type 을 못 박는가</b> — 응답 객체를 그냥 반환하면 스프링이 요청의 Accept 와 컨텐츠 협상을 하고, 협상이 실패하면
     * {@code HttpMediaTypeNotAcceptableException} 이 <b>핸들러 안에서</b> 터진다. 그 예외는 이 클래스가 다시 잡을 수 없어(이미
     * 예외 처리 중) 원 예외가 그대로 재전파되고, 클라이언트는 <b>500 + 빈 본문</b>을 받는다.
     *
     * <p>실제로 그렇게 됐다: SSE 소비자({@code POST /api/query})는 {@code Accept: text/event-stream} 만 보내므로
     * JSON 응답과 협상이 안 됐고, chat 미설정(409 {@code AI_NOT_CONFIGURED})·본문 검증 실패(400)가 모두 <b>빈 본문</b>으로
     * 나갔다. 구 핸들러는 {@code ProblemDetail} 을 썼는데 그건 프레임워크가 Accept 불일치에도 problem 미디어타입으로 기록해 주는 폴백이
     * 있었고, 공통 형식으로 바꾸면서 그 폴백이 사라진 것이다.
     *
     * <p>에러 응답은 <b>협상 대상이 아니다</b>: 요청이 무엇을 받겠다고 했든 실패 사실은 전달돼야 한다. 그래서 여기서 형식을 고정한다 — 클라이언트가 Accept
     * 를 어떻게 보내든 에러 응답이 도착하는 것이 이 클래스가 지켜야 할 계약이다.
     *
     * <p><b>단, 응답이 이미 커밋됐으면 아무것도 쓰지 않는다</b> — {@link #alreadyCommitted()} 참조. 이 검사를 개별 핸들러가 아니라 여기
     * 두는 이유: 규칙("커밋됐으면 쓰지 않는다")은 조건 없는 규칙인데 핸들러마다 손으로 지키면 <b>새 핸들러가 추가될 때마다 빠뜨릴 수 있다</b>. 실제로 타임아웃
     * 핸들러에만 검사가 있고 catch-all 에는 없어서, 스트리밍 중 터진 비-타임아웃 예외가 이미 커밋된 응답에 500 본문을 쓰려 했다. 모든 에러 응답이 이 한
     * 통로를 지나므로 여기서 막으면 규칙이 <b>구조적으로</b> 성립한다.
     */
    private static ResponseEntity<ApiResponse<Void>> envelope(HttpStatus status, ApiError error) {
        if (alreadyCommitted()) {
            log.warn(
                    "응답이 이미 커밋돼 에러 응답을 쓰지 않는다 — {} {} traceId={}",
                    status.value(),
                    error.code(),
                    error.traceId());
            return null;
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail(error));
    }

    /**
     * 응답의 첫 바이트가 이미 나갔는가.
     *
     * <p>SSE 는 200 과 첫 조각을 보낸 뒤에도 예외가 날 수 있다({@code AnswerStreamer} 가 근거 전송 중 실패하면 {@code
     * completeWithError} 로 넘긴다). 그 시점엔 상태·헤더·본문을 더 쓸 수 없어서 응답을 쓰려는 시도 자체가 다시 실패하고, 로그에는 원래 실패 위에
     * "응답 쓰기 실패"가 겹쳐 원인을 가린다. {@code null} 을 반환하면 스프링은 "처리했고 쓸 것은 없다"로 받아들인다(삼키는 게 아니라 응답 통로가 닫힌
     * 것이며, 사실은 위에서 로그로 남긴다).
     *
     * <p>응답 객체를 핸들러 파라미터로 받지 않고 {@link RequestContextHolder} 에서 꺼내는 이유: 그래야 <b>모든</b> 핸들러가 시그니처를
     * 바꾸지 않고 이 보호를 받는다. 컨텍스트가 없으면(비-서블릿 경로) 커밋 여부를 알 수 없으니 "안 됐다"로 보고 정상 경로를 탄다.
     */
    private static boolean alreadyCommitted() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return false;
        }
        HttpServletResponse response = servletAttributes.getResponse();
        return response != null && response.isCommitted();
    }
}
