package io.jenkins.plugins.luxair;

import hudson.util.VersionNumber;
import kong.unirest.*;
import kong.unirest.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


public class ImageTag {

    private static final Logger logger = Logger.getLogger(ImageTag.class.getName());
    private static final Interceptor errorInterceptor = new ErrorInterceptor();

    private ImageTag() {
        throw new IllegalStateException("Utility class");
    }

    public static List<String> getTags(String image, String registry, String filter, String user, String password) {

        String[] authService = getAuthService(registry);
        String token = getAuthToken(authService, image, user, password);
        List<VersionNumber> tags = getImageTagsFromRegistry(image, registry, token);
        return tags.stream().filter(tag -> tag.toString().matches(filter))
            .sorted(Collections.reverseOrder())
            .map(VersionNumber::toString)
            .collect(Collectors.toList());
    }

    private static String[] getAuthService(String registry) {

        String[] rtn = new String[2];
        rtn[0] = "";
        rtn[1] = "";
        String url = registry + "/v2/";

        Unirest.config().reset();
        Unirest.config().enableCookieManagement(false).interceptor(errorInterceptor);
        String headerValue = Unirest.get(url).asEmpty()
            .getHeaders().getFirst("Www-Authenticate");
        Unirest.shutDown();

        String pattern = "Bearer realm=\"(\\S+)\",service=\"(\\S+)\"";
        Matcher m = Pattern.compile(pattern).matcher(headerValue);
        if (m.find()) {
            rtn[0] = m.group(1);
            rtn[1] = m.group(2);
            logger.info("realm:" + rtn[0] + ": service:" + rtn[1] + ":");
        } else {
            logger.warning("No AuthService available from " + url);
        }
        return rtn;
    }

    private static String getAuthToken(String[] authService, String image, String user, String password) {

        String realm = authService[0];
        String service = authService[1];
        String token = "";

        Unirest.config().reset();
        Unirest.config().enableCookieManagement(false).interceptor(errorInterceptor);
        GetRequest request = Unirest.get(realm);
        if (!user.isEmpty() && !password.isEmpty()) {
            logger.info("Basic authentication");
            request = request.basicAuth(user, password);
        } else {
            logger.info("No basic authentication");
        }
        HttpResponse<JsonNode> response = request
            .queryString("service", service)
            .queryString("scope", "repository:" + image + ":pull")
            .asJson();
        if (response.isSuccess()) {
            JSONObject jsonObject = response.getBody().getObject();
            if (jsonObject.has("token")) {
                token = jsonObject.getString("token");
            } else if (jsonObject.has("access_token")) {
                token = jsonObject.getString("access_token");
            } else {
                logger.warning("Token not received");
            }
            logger.info("Token received");
        } else {
            logger.warning("Token not received");
        }
        Unirest.shutDown();

        return token;
    }

    private static List<VersionNumber> getImageTagsFromRegistry(String image, String registry, String token) {
        List<VersionNumber> tags = new ArrayList<>();
        String url = registry + "/v2/{image}/tags/list";

        Unirest.config().reset();
        Unirest.config().enableCookieManagement(false).interceptor(errorInterceptor);
        HttpResponse<JsonNode> response = Unirest.get(url)
            .header("Authorization", "Bearer " + token)
            .routeParam("image", image)
            .asJson();
        if (response.isSuccess()) {
            logger.info("HTTP status: " + response.getStatusText());
            response.getBody().getObject()
                .getJSONArray("tags")
                .forEach(item -> tags.add(new VersionNumber(item.toString())));
        } else {
            logger.warning("HTTP status: " + response.getStatusText());
        }
        Unirest.shutDown();

        return tags;
    }
}