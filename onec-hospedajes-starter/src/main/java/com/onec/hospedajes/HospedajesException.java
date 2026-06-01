package com.onec.hospedajes;

/** Raised when communication with SES.HOSPEDAJES fails at the transport or protocol level. */
public class HospedajesException extends RuntimeException {

    public HospedajesException(String message) {
        super(message);
    }

    public HospedajesException(String message, Throwable cause) {
        super(message, cause);
    }
}
