package com.axway.apim;


import com.axway.apim.service.APIMService;
import com.axway.apim.service.APIManager;
import com.axway.apim.service.CSRFTokenInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import okhttp3.*;
import okhttp3.logging.HttpLoggingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@CommandLine.Command(description = "APIM Deployment",
    name = "apim", mixinStandardHelpOptions = true, version = "1.0.1")
public class App implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(App.class);

    @CommandLine.Option(required = true, names = {"-e", "--url"}, description = "API Manager URL")
    private String url;

    @CommandLine.Option(names = {"-c", "--clusterurl"}, description = "API Manager Cluster URLs")
    private final Deque<String> urls = null;

    @CommandLine.Option(required = true, names = {"-u", "--username"}, description = "API Manager Username")
    private String username;

    @CommandLine.Option(required = true, names = {"-p", "--password"}, description = "API Manager Password")
    private String password;

    @CommandLine.Option(required = true, names = {"-n", "--name"}, description = "API  name")
    private String apiName;

    @CommandLine.Option(required = true, names = {"-o", "--organization_name"}, description = "API Manager Development Organization name")
    private String orgName;

    @CommandLine.Option(names = {"-s", "--skipSSL"}, description = "SKIP SSL server validation")
    private boolean skipSSL = false;

    @CommandLine.Option(required = true, names = {"-i", "--openapi"}, description = "Open API File location")
    private File openAPI;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
        int exitCode = new CommandLine(new App()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        JsonNode openAPIJsonNode;
        try {
            openAPIJsonNode = objectMapper.readTree(openAPI);
        } catch (IOException e) {
            logger.error("Unable to parse open API json file : {}", openAPI, e);
            return 1;
        }

        APIManager apiManager = null;
        try {
            apiManager = createAPIMService(url, username, password);
            if (apiManager == null)
                return 1;
            APIMService apimService = apiManager.getApimService();
            String orgId = getOrgId(apimService, orgName);
            if (orgId == null) {
                return 1;
            }
            Response<List<Map<String, Object>>> response = apimService.listFrontendAPIsByName("name", "eq", apiName).execute();
            if (response.isSuccessful()) {
                List<Map<String, Object>> apis = response.body();
                if (apis == null || apis.size() == 0) {
                    logger.error(" No Match for the API : {}", apiName);
                    return 1;
                }
                if (apis.size() > 1) {
                    logger.error("More than on API matched : {}", apis);
                    return 1;
                }
                Map<String, Object> api = apis.get(0);
                logger.info("{}", apis);
                String id = (String) api.get("id");
                String backendAPIId = (String) api.get("apiId");
                logger.info("Found the API : {} from API Catalog with id : {}", apiName, id);
                logger.info("Downloading Backend API Definition with id : {}", backendAPIId);
                Response<ResponseBody> downloadResponse = apimService.downloadBackendAPI(backendAPIId, true, "swagger.json").execute();
                logger.info("Backend api download complete wit status code :{}", downloadResponse.code());
                if (downloadResponse.isSuccessful()) {
                    JsonNode existingOpenAPI = objectMapper.readTree(downloadResponse.body().string());
                    if (!existingOpenAPI.equals(openAPIJsonNode)) {
                        logger.info(" The openapi definition is changed ");
                        logger.info("Creating new backend API");
                        String newBackendID = createBackend(apimService, orgId);
                        if (newBackendID != null) {
                            logger.info("Backend API created with id : {}", newBackendID);
                            logger.info("Creating new Frontend API");
                            Map<String, Object> newAPI = createFrontend(apimService, newBackendID, orgId);
                            newAPI = updateNewAPIWithExistingConfig(apimService, api, newAPI);
                            if (newAPI != null) {
                                String newAPIId = (String) newAPI.get("id");
                                logger.info("Frontend API created with id : {}", newAPIId);
                                int statusCode = publishAPI(apimService, newAPIId);
                                if (statusCode == 201) {
                                    logger.info("Upgrading API {} with new API {}", id, newAPIId);
                                    statusCode = upgradeAPI(apimService, id, newAPIId);
                                    if (statusCode == 204) {

                                        if (checkCatalogForPublishedState(apimService, newAPIId)) {
                                            statusCode = deprecateAPI(apimService, id);
                                            if (statusCode == 201) {
                                                logger.info("Deprecate API with id : {}", id);
                                                return 0;
                                            }
                                        } else {
                                            logger.info("Un publish API with id : {}", id);
                                            statusCode = unPublishAPI(apimService, id);
                                            if (statusCode == 201) {
                                                logger.info("Delete API with id : {}", id);
                                                statusCode = deleteAPI(apimService, id);
                                                if (statusCode == 204) {
                                                    logger.info("Delete Backend API with id : {}", backendAPIId);
                                                    statusCode = deleteBackendAPI(apimService, backendAPIId);
                                                    if (statusCode == 204)
                                                        return 0;
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                        }
                    } else {
                        logger.info(" The openapi definition is not changed, exiting.. ");
                    }
                }
                return 1;
            }
        } catch (IOException e) {
            logger.error("Error processing", e);
        } finally {

            if (apiManager != null) {
                APIMService apimService = apiManager.getApimService();
                apimService.logout();
            }
        }
        return 0;
    }


    private OkHttpClient newClient(CookieJar cookieJar, HttpLoggingInterceptor httpLoggingInterceptor, CSRFTokenInterceptor csrfTokenInterceptor) {

        return new OkHttpClient.Builder()
            .addInterceptor(httpLoggingInterceptor)
            .addInterceptor(csrfTokenInterceptor)
            .cookieJar(cookieJar)
            .followRedirects(false)
            .build();
    }

    public boolean checkCatalogForPublishedState(APIMService apimService, String id) throws IOException {
        String state = "";
        boolean exit = false;
        Object newObj = new Object();
        do {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                logger.error("Error ", e);
            }
            Response<ResponseBody> catalogResponse = apimService.listCatalogByName("id", "eq", id).execute();
            logger.info("Get Catalog By API Name is complete with status code :{}", catalogResponse.code());
            if (!catalogResponse.isSuccessful()) {
                logger.error("API  {} not found in API manger catalog", apiName);
                return false;
            }
            try {
                state = JsonPath.parse(catalogResponse.body().string()).read("$.[0].state", String.class);
                logger.info("API : {} , State : {}", id, state);
            } catch (PathNotFoundException e) {
                logger.info("Catalog is not yet created");
            }

            if (state.equals("published")) {
                if (newObj.equals(apimService)) {
                    apimService.logout();
                }
                if (urls == null || urls.isEmpty()) {
                    exit = true;

                } else {
                    String secondaryURL = urls.pop();
                    logger.info("Checking secondary URL : {}", secondaryURL);
                    APIManager apiManager = createAPIMService(secondaryURL, username, password);
                    if (apiManager != null) {
                        newObj = apiManager.getApimService();
                    } else {
                        logger.error("Unable to connect to API manager : {}", secondaryURL);
                    }
                }
            }
        } while (!exit);
        return true;
    }

    public String getOrgId(APIMService apimService, String orgName) throws IOException {
        Response<ResponseBody> orgResponse = apimService.getOrganizationByName("name", "eq", orgName).execute();
        logger.info("Get Org name complete with status code :{}", orgResponse.code());

        if (!orgResponse.isSuccessful()) {
            logger.error("Organization {} not found in API manger", orgName);
            return null;
        }

        try {
            return JsonPath.parse(orgResponse.body().string()).read("$.[0].id", String.class);
        } catch (PathNotFoundException e) {
            logger.error("Unable to retrieve Organization detail", e);
            return null;
        }
    }

    public String createBackend(APIMService apimService, String orgId) throws IOException {
        RequestBody requestBody = RequestBody.create(openAPI, MediaType.get("application/octet-stream"));
        RequestBody orgRequestBody = RequestBody.create(orgId.getBytes(StandardCharsets.UTF_8));
        RequestBody typeRequestBody = RequestBody.create("swagger".getBytes(StandardCharsets.UTF_8));
        RequestBody apiNameRequestBody = RequestBody.create(apiName.getBytes(StandardCharsets.UTF_8));
        Response<ResponseBody> backendResponse = apimService.createBackend(requestBody, orgRequestBody, typeRequestBody, apiNameRequestBody).execute();
        logger.info("Create Backend  complete with status code :{}", backendResponse.code());

        if (!backendResponse.isSuccessful()) {
            logger.error("Unable to create backend API");
            return null;
        }

        try {
            return JsonPath.parse(backendResponse.body().string()).read("$.id", String.class);
        } catch (PathNotFoundException e) {
            logger.error("Unable to retrieve Backend detail", e);
            return null;
        }
    }

    public Map<String, Object> createFrontend(APIMService apimService, String backendAPIId, String orgId) throws IOException {
        Map<String, String> map = new HashMap<>();
        map.put("apiId", backendAPIId);
        map.put("organizationId", orgId);
        Response<Map<String, Object>> frontendResponse = apimService.createFrontend(map).execute();
        logger.info("Create Frontend  complete with status code :{}", frontendResponse.code());

        if (!frontendResponse.isSuccessful()) {
            logger.error("Unable to create Frontend API");
            return null;
        }
        return frontendResponse.body();
    }

    public int publishAPI(APIMService apimService, String apiId) throws IOException {

        Response<ResponseBody> frontendResponse = apimService.publishAPI(apiId, apiName, null).execute();
        logger.info("Publish Frontend API complete with status code :{}", frontendResponse.code());
        return frontendResponse.code();

    }

    public int deprecateAPI(APIMService apimService, String apiId) throws IOException {
        Instant instant = Instant.now();
        String retirementDate = instant.plusMillis(5000).toString();
        //  String retirementDate = instant.plus(2, ChronoUnit.MINUTES).toString();
        Response<ResponseBody> frontendResponse = apimService.deprecateAPI(retirementDate, apiId).execute();
        logger.info("Deprecate Frontend API complete with status code :{}", frontendResponse.code());
        return frontendResponse.code();

    }

    public int unPublishAPI(APIMService apimService, String apiId) throws IOException {

        Response<ResponseBody> frontendResponse = apimService.unPublishAPI(apiId).execute();
        logger.info("UnPublish Frontend API complete with status code :{}", frontendResponse.code());
        return frontendResponse.code();

    }

    public int deleteAPI(APIMService apimService, String apiId) throws IOException {

        Response<Void> frontendResponse = apimService.deleteAPI(apiId).execute();
        logger.info("Delete Frontend API complete with status code :{}", frontendResponse.code());
        return frontendResponse.code();

    }

    public int deleteBackendAPI(APIMService apimService, String apiId) throws IOException {

        Response<Void> frontendResponse = apimService.deleteBackendAPI(apiId).execute();
        logger.info("Delete Backend API complete with status code :{}", frontendResponse.code());
        return frontendResponse.code();

    }


    public int upgradeAPI(APIMService apimService, String newAPIId, String oldAPIId) throws IOException {

        Response<Void> frontendResponse = apimService.upgradeAPI(newAPIId, oldAPIId).execute();
        logger.info("Upgrade Frontend API complete with status code :{}", frontendResponse.code());
        return frontendResponse.code();

    }

    public Map<String, Object> updateNewAPIWithExistingConfig(APIMService apimService, Map<String, Object> oldAPI, Map<String, Object> newAPI) throws IOException {

        newAPI.put("path", oldAPI.get("path"));
        newAPI.put("authenticationProfiles", oldAPI.get("authenticationProfiles"));
        newAPI.put("securityProfiles", oldAPI.get("securityProfiles"));
        newAPI.put("outboundProfiles", oldAPI.get("outboundProfiles"));
        newAPI.put("serviceProfiles", oldAPI.get("serviceProfiles"));
        newAPI.put("inboundProfiles", oldAPI.get("inboundProfiles"));
        String newAPIId = (String) newAPI.get("id");

        Response<Map<String, Object>> frontendResponse = apimService.updateFrontend(newAPIId, newAPI).execute();
        logger.info("Copy Frontend configuration from old API to new API complete with status code :{}", frontendResponse.code());

        if (!frontendResponse.isSuccessful()) {
            logger.error("Unable to copy configuration");
            return null;
        }
        return frontendResponse.body();
    }


    private OkHttpClient newUnsecureSSLClient(CookieJar cookieJar, HttpLoggingInterceptor httpLoggingInterceptor, CSRFTokenInterceptor csrfTokenInterceptor) {
        final TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[]{};
                }
            }
        };

        try {
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
            return new OkHttpClient.Builder()
                .addInterceptor(httpLoggingInterceptor)
                .addInterceptor(csrfTokenInterceptor)
                // .addInterceptor(basicAuthInterceptor)
                .cookieJar(cookieJar)
                .followRedirects(false)
                .connectTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .hostnameVerifier((s, sslSession) -> true)
                .sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0])
                .build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            e.printStackTrace();
        }
        return null;
    }

    public APIManager createAPIMService(String httpURL, String username, String password) throws IOException {
        final Map<String, List<Cookie>> cookieStore = new HashMap<>();
        APIManager apiManager = new APIManager();

        CookieJar cookieJar = new CookieJar() {
            @Override
            public void saveFromResponse(HttpUrl httpUrl, List<Cookie> list) {
                cookieStore.put(httpUrl.host(), list);
            }

            @Override
            public List<Cookie> loadForRequest(HttpUrl httpUrl) {
                List<Cookie> cookies = cookieStore.get(httpUrl.host());
                return cookies != null ? cookies : new ArrayList<>();
            }
        };

        CSRFTokenInterceptor csrfTokenInterceptor = new CSRFTokenInterceptor(apiManager);
        // String endpoint = url + "";
        //BasicAuthInterceptor basicAuthInterceptor = new BasicAuthInterceptor(username, new String(password));
        HttpLoggingInterceptor interceptor = new HttpLoggingInterceptor();
        interceptor.setLevel(HttpLoggingInterceptor.Level.BASIC);
        OkHttpClient client;

        if (skipSSL) {
            client = newUnsecureSSLClient(cookieJar, interceptor, csrfTokenInterceptor);
        } else {
            client = newClient(cookieJar, interceptor, csrfTokenInterceptor);
        }

        Retrofit apimRetrofit = new Retrofit.Builder()
            .baseUrl(httpURL)
            .client(client)
            .addConverterFactory(JacksonConverterFactory.create())
            .build();

        APIMService apimService = apimRetrofit.create(APIMService.class);
        Response<ResponseBody> loginResponse = apimService.login(username, password).execute();
        if (loginResponse.code() == 303) {
            String csrfToken = loginResponse.headers().get("CSRF-Token");
            apiManager.setApimService(apimService);
            apiManager.setCsrfToken(csrfToken);
            return apiManager;
        }
        return null;
    }


}

