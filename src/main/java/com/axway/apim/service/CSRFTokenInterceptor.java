package com.axway.apim.service;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public class CSRFTokenInterceptor implements Interceptor {

    private APIManager apiManager;
    public CSRFTokenInterceptor(APIManager apiManager){
        this.apiManager = apiManager;
    }
    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        Request originalRequest = chain.request();
        if (originalRequest.url().toString().endsWith("login")) {
            return chain.proceed(originalRequest);
        }

        String token = apiManager.getCsrfToken();
        if(token == null){
            return chain.proceed(originalRequest);
        }

        Request newRequest = originalRequest.newBuilder()
                .header("CSRF-Token", token)
                .build();
        return chain.proceed(newRequest);

    }
}
