package in.gov.ipie.common.security.hmac;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Buffers the request body into memory exactly once (in the constructor) so it can be read
 * multiple times afterward - once by {@link HmacSignatureVerificationFilter} to compute the body
 * hash, and again downstream (a controller's {@code @RequestBody}) without either read starving
 * the other. A raw {@code HttpServletRequest}'s input stream is otherwise consumable only once;
 * {@code ContentCachingRequestWrapper} (Spring's own class) does not solve this - it caches bytes
 * as they're read, but does not make a *second* {@code getInputStream()} call return them, so a
 * filter that reads the body still leaves nothing for the controller afterward.
 *
 * <p>Buffers fully into memory - acceptable for a signature-verification checkpoint applied only
 * to a small, deliberately-chosen set of high-sensitivity endpoints, not every request.
 *
 * <p>{@code final} - the constructor can throw (reading the body eagerly), and a subclass could
 * otherwise observe a partially-constructed instance via an overridden method called from a
 * finalizer before the constructor completes (SpotBugs {@code CT_CONSTRUCTOR_THROW}, SEI CERT
 * OBJ-11); sealing the class is the simplest of that rule's recommended fixes here, since this
 * type has no legitimate reason to be extended.
 */
final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.cachedBody = request.getInputStream().readAllBytes();
    }

    byte[] cachedBody() {
        return cachedBody;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return byteStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("Async body reading is not supported by this cached wrapper");
            }

            @Override
            public int read() {
                return byteStream.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(cachedBody), StandardCharsets.UTF_8));
    }
}
