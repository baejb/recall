package com.recall.common.exception;

/**
 * 501 — 골격만 있고 로직이 아직 없는 단계.
 *
 * <p>500 으로 터뜨리지 않는 이유: 호출자가 "서버가 고장났다"와 "이 기능은 아직 없다"를 구분할 수 있어야 한다. 구현이 들어오면 이 예외를 던지는 자리가 사라지므로,
 * 던지는 곳이 0 이 되면 이 클래스도 지운다.
 */
public class NotImplementedException extends ApiException {

    /**
     * @param what 무엇이 아직 없는지 — 단계 이름을 함께 적는다(예: "S4 판정 리랭크")
     */
    public NotImplementedException(String what) {
        super(ErrorCode.NOT_IMPLEMENTED, "아직 구현되지 않았습니다: " + what);
    }
}
