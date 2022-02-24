package com.axway.apim.service;

public class APIManager {
    private APIMService apimService;
    private String csrfToken;

    public APIMService getApimService() {
        return apimService;
    }

    public void setApimService(APIMService apimService) {
        this.apimService = apimService;
    }

    public String getCsrfToken() {
        return csrfToken;
    }

    public void setCsrfToken(String csrfToken) {
        this.csrfToken = csrfToken;
    }
}
