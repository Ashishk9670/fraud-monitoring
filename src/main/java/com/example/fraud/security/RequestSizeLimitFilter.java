package com.example.fraud.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects oversized request bodies with 413 (declared Content-Length) before any parsing happens. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeLimitFilter extends OncePerRequestFilter {
    private final long maxBytes;

    public RequestSizeLimitFilter(SecurityProperties props) {
        this.maxBytes = props.maxRequestSize().toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (req.getContentLengthLong() > maxBytes) {
            res.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            res.setContentType("application/problem+json");
            res.getWriter().write("{\"title\":\"Payload Too Large\",\"status\":413,\"detail\":\"Request body exceeds "
                    + maxBytes + " bytes\"}");
            return;
        }
        // Chunked / undeclared length: cap what the application may read (surfaces as a 400 read error).
        chain.doFilter(req.getContentLengthLong() < 0 ? new Limited(req, maxBytes) : req, res);
    }

    private static final class Limited extends HttpServletRequestWrapper {
        private final long max;

        Limited(HttpServletRequest r, long max) {
            super(r);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();
            return new ServletInputStream() {
                long read;

                @Override public int read() throws IOException {
                    int b = in.read();
                    if (b >= 0 && ++read > max) throw new IOException("Request body too large");
                    return b;
                }
                @Override public int read(byte[] buf, int off, int len) throws IOException {
                    int n = in.read(buf, off, len);
                    if (n > 0 && (read += n) > max) throw new IOException("Request body too large");
                    return n;
                }
                @Override public boolean isFinished() { return in.isFinished(); }
                @Override public boolean isReady() { return in.isReady(); }
                @Override public void setReadListener(ReadListener l) { in.setReadListener(l); }
            };
        }
    }
}
