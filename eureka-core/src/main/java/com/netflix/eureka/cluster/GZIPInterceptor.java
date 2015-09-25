package com.netflix.eureka.cluster;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.ext.ReaderInterceptor;
import javax.ws.rs.ext.ReaderInterceptorContext;
import javax.ws.rs.ext.WriterInterceptor;
import javax.ws.rs.ext.WriterInterceptorContext;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class GZIPInterceptor implements WriterInterceptor, ReaderInterceptor {

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        Object contentEncoding = context.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
        if (hasEntity(context) && "gzip".equals(contentEncoding)) {
            final OutputStream outputStream = context.getOutputStream();
            context.setOutputStream(new GZIPOutputStream(outputStream));
        }
        context.proceed();
    }

    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        Object contentEncoding = context.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
        if ("gzip".equals(contentEncoding)) {
            context.setInputStream(new GZIPInputStream(context.getInputStream()));
        }
        return context.proceed();
    }

    private boolean hasEntity(WriterInterceptorContext context) {
        return Objects.nonNull(context.getEntity());
    }

}
