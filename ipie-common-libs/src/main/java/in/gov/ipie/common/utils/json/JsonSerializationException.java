package in.gov.ipie.common.utils.json;

/** Unchecked wrapper around Jackson's checked {@link com.fasterxml.jackson.core.JsonProcessingException}. */
public class JsonSerializationException extends RuntimeException {

    public JsonSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
