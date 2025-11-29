package com.shortener;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.HashMap;

public class RedirectHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final DateTimeFormatter dateFormatter;
    private final ZoneId timeZone;

    private final Map<String, String> corsHeaders = Map.of(
            "Access-Control-Allow-Origin", "*",
            "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS",
            "Access-Control-Allow-Headers", "Content-Type, X-Amz-Date, Authorization, X-Api-Key, X-Amz-Security-Token, X-Amz-User-Agent",
            "Access-Control-Max-Age", "86400"
    );

    public RedirectHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.tableName = System.getenv("TABLE_NAME");
        this.dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        this.timeZone = ZoneId.of("America/Bogota");
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            if ("OPTIONS".equalsIgnoreCase(event.getRequestContext().getHttp().getMethod())) {
                context.getLogger().log("Handling OPTIONS preflight request");
                return APIGatewayV2HTTPResponse.builder()
                        .withStatusCode(200)
                        .withHeaders(corsHeaders)
                        .withBody("")
                        .build();
            }

            context.getLogger().log("Received event: " + event.toString());

            String code = event.getPathParameters() != null ?
                    event.getPathParameters().get("code") : null;

            if (code == null || code.trim().isEmpty()) {
                context.getLogger().log("Code parameter is missing");
                return createErrorResponse(400, "Code parameter is required");
            }

            context.getLogger().log("Looking up code: " + code);

            GetItemRequest getItemRequest = GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("code", AttributeValue.builder().s(code).build()))
                    .build();

            GetItemResponse result = dynamoDbClient.getItem(getItemRequest);

            if (result.item() == null || !result.item().containsKey("originalUrl")) {
                context.getLogger().log("Code not found: " + code);
                return createErrorResponse(404, "URL not found for code: " + code);
            }

            String originalUrl = result.item().get("originalUrl").s();
            context.getLogger().log("Redirecting to: " + originalUrl);

            incrementVisitCounter(code, context);
            incrementVisitsByDate(code, context);

            Map<String, String> responseHeaders = new HashMap<>(corsHeaders);
            responseHeaders.put("Location", originalUrl);
            responseHeaders.put("Cache-Control", "no-cache");

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(302)
                    .withHeaders(responseHeaders)
                    .withBody("")
                    .build();

        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            e.printStackTrace();
            return createErrorResponse(500, "Internal server error: " + e.getMessage());
        }
    }

    private void incrementVisitCounter(String code, Context context) {
        try {
            context.getLogger().log("Incrementing visit counter for code: " + code);

            // Actualizar el contador totalVisits
            UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("code", AttributeValue.builder().s(code).build()))
                    .updateExpression("SET totalVisits = if_not_exists(totalVisits, :zero) + :inc")
                    .expressionAttributeValues(Map.of(
                            ":inc", AttributeValue.builder().n("1").build(),
                            ":zero", AttributeValue.builder().n("0").build()
                    ))
                    .build();

            dynamoDbClient.updateItem(updateRequest);
            context.getLogger().log("Successfully incremented visit counter for code: " + code);

        } catch (Exception e) {
            context.getLogger().log("Error incrementing counter: " + e.getMessage());
        }
    }

    private void incrementVisitsByDate(String code, Context context) {
        try {
            context.getLogger().log("Incrementing visitsByDate for code: " + code);

            String today = LocalDate.now(timeZone).format(dateFormatter);
            context.getLogger().log("Today's date with timezone " + timeZone + ": " + today);

            GetItemRequest getRequest = GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("code", AttributeValue.builder().s(code).build()))
                    .projectionExpression("visitsByDate")
                    .build();

            GetItemResponse getResult = dynamoDbClient.getItem(getRequest);
            boolean visitsByDateExists = getResult.item() != null && getResult.item().containsKey("visitsByDate");

            UpdateItemRequest updateRequest;

            if (!visitsByDateExists) {
                Map<String, AttributeValue> initialMap = new HashMap<>();
                initialMap.put(today, AttributeValue.builder().n("1").build());

                updateRequest = UpdateItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("code", AttributeValue.builder().s(code).build()))
                        .updateExpression("SET visitsByDate = :initialMap")
                        .expressionAttributeValues(Map.of(
                                ":initialMap", AttributeValue.builder().m(initialMap).build()
                        ))
                        .build();
            } else {
                updateRequest = UpdateItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("code", AttributeValue.builder().s(code).build()))
                        .updateExpression("SET visitsByDate.#today = if_not_exists(visitsByDate.#today, :zero) + :inc")
                        .expressionAttributeNames(Map.of(
                                "#today", today
                        ))
                        .expressionAttributeValues(Map.of(
                                ":inc", AttributeValue.builder().n("1").build(),
                                ":zero", AttributeValue.builder().n("0").build()
                        ))
                        .build();
            }

            dynamoDbClient.updateItem(updateRequest);
            context.getLogger().log("Successfully incremented visitsByDate for code: " + code + " on date: " + today);

        } catch (Exception e) {
            context.getLogger().log("Error incrementing visitsByDate: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private APIGatewayV2HTTPResponse createErrorResponse(int statusCode, String message) {
        Map<String, String> responseHeaders = new HashMap<>(corsHeaders);
        responseHeaders.put("Content-Type", "application/json");

        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(statusCode)
                .withHeaders(responseHeaders)
                .withBody("{\"error\":\"" + message + "\"}")
                .build();
    }
}