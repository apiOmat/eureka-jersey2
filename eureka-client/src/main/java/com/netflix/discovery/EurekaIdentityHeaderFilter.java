package com.netflix.discovery;

import com.netflix.appinfo.AbstractEurekaIdentity;

import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.core.MultivaluedMap;
import java.io.IOException;

public class EurekaIdentityHeaderFilter implements ClientRequestFilter {

    private final AbstractEurekaIdentity authInfo;

    public EurekaIdentityHeaderFilter(AbstractEurekaIdentity authInfo) {
        this.authInfo = authInfo;
    }

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        MultivaluedMap<String, Object> headers = requestContext.getHeaders();
        headers.putSingle(AbstractEurekaIdentity.AUTH_NAME_HEADER_KEY, authInfo.getName());
        headers.putSingle(AbstractEurekaIdentity.AUTH_VERSION_HEADER_KEY, authInfo.getVersion());
        if (authInfo.getId() != null) {
            headers.putSingle(AbstractEurekaIdentity.AUTH_ID_HEADER_KEY, authInfo.getId());
        }
    }
}
