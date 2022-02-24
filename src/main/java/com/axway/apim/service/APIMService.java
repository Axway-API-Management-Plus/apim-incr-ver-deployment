package com.axway.apim.service;


import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.*;

import java.util.List;
import java.util.Map;

public interface APIMService {

    @FormUrlEncoded
    @POST("/api/portal/v1.3/login")
    Call<ResponseBody> login(@Field("username")String username, @Field("password") String password);

    @DELETE("/api/portal/v1.3/login")
    Call<ResponseBody> logout();

    @GET("/api/portal/v1.3/apirepo/{id}/download")
    Call<ResponseBody> downloadBackendAPI(@Path("id")String id, @Query("original") boolean original, @Query("filename") String filename);

    @Multipart
    @POST("/api/portal/v1.3/apirepo/import")
    Call<ResponseBody> createBackend(@Part("file") RequestBody requestBody, @Part("organizationId") RequestBody organizationId, @Part("type") RequestBody type, @Part("name") RequestBody name);

    @POST("/api/portal/v1.3/proxies")
    Call<Map<String, Object>> createFrontend(@Body Map<String, String> body);

    @PUT("/api/portal/v1.3/proxies/{id}")
    Call<Map<String, Object>> updateFrontend(@Path("id")String id, @Body Map<String, Object> body);

    @FormUrlEncoded
    @POST("/api/portal/v1.3/proxies/{id}/publish")
    Call<ResponseBody> publishAPI(@Path("id")String id, @Field("name")String name, @Field("vhost")String vhost);

    @POST("/api/portal/v1.3/proxies/{id}/unpublish")
    Call<ResponseBody> unPublishAPI(@Path("id")String id);

    @DELETE("/api/portal/v1.3/proxies/{id}")
    Call<Void> deleteAPI(@Path("id")String id);

    @DELETE("/api/portal/v1.3/apirepo/{id}")
    Call<Void> deleteBackendAPI(@Path("id")String id);

    @POST("/api/portal/v1.3/proxies/{id}/deprecate")
    @FormUrlEncoded
    Call<ResponseBody> deprecateAPI(@Field("retirementDate") String retirementDate, @Path("id")String id);

    @FormUrlEncoded
    @POST("/api/portal/v1.3/proxies/upgrade/{id}")
    Call<Void> upgradeAPI(@Path("id")String id, @Field("upgradeApiId") String upgradeApiId);

    @GET("/api/portal/v1.3/proxies")
    Call<List<Map<String, Object>>> listFrontendAPIsByName(@Query("field") String field, @Query("op") String op, @Query("value") String value);

    @GET("/api/portal/v1.3/organizations")
    Call<ResponseBody> getOrganizationByName(@Query("field") String field, @Query("op") String op, @Query("value") String value);

    @GET("/api/portal/v1.4/discovery/swagger/apis")
    Call<ResponseBody> listCatalogByName(@Query("field") String field, @Query("op") String op, @Query("value") String value);

    @POST("/applications/{appId}/apis")
    Call<ResponseBody> addAPItoApplication( @Path("appId")String id);


}
