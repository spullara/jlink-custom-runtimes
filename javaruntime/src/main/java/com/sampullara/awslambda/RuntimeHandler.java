package com.sampullara.awslambda;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.LambdaRuntime;
import com.amazonaws.services.lambda.runtime.events.CognitoEvent;
import com.amazonaws.services.lambda.runtime.events.ConfigEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.KinesisEvent;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingJsonFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;

import static java.net.http.HttpResponse.BodyHandlers;

/**
 * Custom runtime handler for executing Java 11 applications
 */
public class RuntimeHandler {
  private final static MappingJsonFactory mjf = new MappingJsonFactory();

  private final static HttpClient hc = HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  public static void main(String[] args) throws IOException, InterruptedException {
    String runtimeApi = System.getenv("AWS_LAMBDA_RUNTIME_API");
    String base = "http://" + runtimeApi + "/2018-06-01/runtime/";
    String invocationBase = base + "invocation/";
    String next = invocationBase + "next";

    String handlerRef = System.getenv("_HANDLER");
    int split = handlerRef.lastIndexOf(".");
    String className = handlerRef.substring(0, split);
    String methodName = handlerRef.substring(split + 1);

    try {
      Class<?> handlerClass = Class.forName(className);
      Object handler = handlerClass.getConstructor().newInstance();
      Method[] methods = handlerClass.getMethods();
      Method method = Arrays.stream(methods)
              .filter(m -> m.getName().equals(methodName))
              .filter(m -> m.getParameterCount() <= 2)
              .filter(m -> {
                int pc = m.getParameterCount();
                return pc == 1 || m.getParameters()[pc - 1].getType().equals(Context.class);
              })
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Handler Method Resolution\n" +
                      "\n" +
                      "It will look for the method named with 1 or 2 parameters where the only or second may be a Context.\n" +
                      "\n" +
                      "If your Java code contains multiple methods with same name as the handler name, then AWS Lambda uses the following rules to pick a method to invoke:\n" +
                      "\n" +
                      "Select the method with the largest number of parameters.\n" +
                      "\n" +
                      "If two or more methods have the same number of parameters, AWS Lambda selects the method that has the Context as the last parameter.\n" +
                      "\n" +
                      "If none or all of these methods have the Context parameter, then the behavior is undefined."));

      //noinspection InfiniteLoopStatement
      while (true) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(next)).GET().build();
        HttpResponse<String> hr = hc.send(request, BodyHandlers.ofString());
        HttpHeaders headers = hr.headers();
        String requestId = headers.firstValue("Lambda-Runtime-Aws-Request-Id").orElseThrow();
        String deadlineMs = headers.firstValue("Lambda-Runtime-Deadline-Ms").orElseThrow();
        String functionArn = headers.firstValue("Lambda-Runtime-Invoked-Function-Arn").orElseThrow();
        headers.firstValue("Lambda-Runtime-Trace-Id")
                .ifPresent(traceId -> System.setProperty("_X_AMZN_TRACE_ID", traceId));
        try {
          String value = hr.body();
          boolean isJson = headers.firstValue("Content-Type").orElseThrow().contains("json");
          Object[] parameters = new Object[method.getParameterCount()];
          Class<?> firstParameterType = method.getParameterTypes()[0];
          setContext(requestId, deadlineMs, functionArn, parameters, firstParameterType);
          if (!firstParameterType.equals(Context.class)) {
            String parameterClassName = firstParameterType.getCanonicalName();
            switch (parameterClassName) {
              case "java.lang.String":
                parameters[0] = isJson ? node(value).asText() : value;
                break;
              case "java.lang.Integer":
              case "int":
                parameters[0] = isJson ? node(value).asInt() : Integer.parseInt(value);
                break;
              case "java.lang.Long":
              case "long":
                parameters[0] = isJson ? node(value).asLong() : Long.parseLong(value);
                break;
              case "java.lang.Float":
              case "float":
                parameters[0] = isJson ? ((float) node(value).asDouble()) : Float.parseFloat(value);
                break;
              case "java.lang.Double":
              case "double":
                parameters[0] = isJson ? node(value).asDouble() : Double.parseDouble(value);
                break;
              case "java.lang.Boolean":
              case "boolean":
                parameters[0] = isJson ? node(value).asBoolean() : Boolean.parseBoolean(value);
                break;
              case "com.amazonaws.services.lambda.runtime.events.S3Event":
                parameters[0] = new S3Event(S3Event.parseJson(value).getRecords());
                break;
              case "com.amazonaws.services.lambda.runtime.events.CognitoEvent":
                parameters[0] = mjf.createParser(value).readValueAs(CognitoEvent.class);
                break;
              case "com.amazonaws.services.lambda.runtime.events.ConfigEvent":
                parameters[0] = mjf.createParser(value).readValueAs(ConfigEvent.class);
                break;
              case "com.amazonaws.services.lambda.runtime.events.DynamodbEvent":
                parameters[0] = mjf.createParser(value).readValueAs(DynamodbEvent.class);
                break;
              case "com.amazonaws.services.lambda.runtime.events.KinesisEvent":
                parameters[0] = mjf.createParser(value).readValueAs(KinesisEvent.class);
                break;
              case "com.amazonaws.services.lambda.runtime.events.SNSEvent":
                parameters[0] = mjf.createParser(value).readValueAs(SNSEvent.class);
                break;
              default:
                parameters[0] = mjf.createParser(value).readValueAs(firstParameterType);
                break;
            }
          }

          // Invoke the lambda request handler
          Object invoked = method.invoke(handler, parameters);

          // Generate the serialized version of the result
          StringWriter sw = new StringWriter();
          JsonGenerator g = mjf.createGenerator(sw);
          g.writeObject(invoked);
          g.flush();

          // Construct response
          HttpRequest responsePost = HttpRequest.newBuilder(URI.create(invocationBase + requestId + "/response"))
                  .POST(HttpRequest.BodyPublishers.ofString(sw.toString()))
                  .header("Content-Type", "application/json")
                  .build();
          hc.send(responsePost, BodyHandlers.discarding());
        } catch (Throwable e) {
          e.printStackTrace();
          // Normal error from execution
          sendError(e, URI.create(invocationBase + requestId + "/error"));
        }
      }
    } catch (Throwable e) {
      e.printStackTrace();
      // Initialization error
      sendError(e, URI.create(base + "init/error"));
    }
  }

  private static void setContext(String requestId, String deadlineMs, String functionArn, Object[] parameters, Class<?> firstParameterType) {
    Context context = new Context() {
      @Override
      public String getAwsRequestId() {
        return requestId;
      }

      @Override
      public String getLogGroupName() {
        return System.getenv("AWS_LAMBDA_LOG_GROUP_NAME");
      }

      @Override
      public String getLogStreamName() {
        return System.getenv("AWS_LAMBDA_LOG_STREAM_NAME");
      }

      @Override
      public String getFunctionName() {
        return System.getenv("AWS_LAMBDA_FUNCTION_NAME");
      }

      @Override
      public String getFunctionVersion() {
        return System.getenv("AWS_LAMBDA_FUNCTION_VERSION");
      }

      @Override
      public String getInvokedFunctionArn() {
        return functionArn;
      }

      @Override
      public CognitoIdentity getIdentity() {
        throw new UnsupportedOperationException();
      }

      @Override
      public ClientContext getClientContext() {
        throw new UnsupportedOperationException();
      }

      @Override
      public int getRemainingTimeInMillis() {
        return (int) (Long.parseLong(deadlineMs) - System.currentTimeMillis());
      }

      @Override
      public int getMemoryLimitInMB() {
        return Integer.parseInt(System.getenv("AWS_LAMBDA_FUNCTION_MEMORY_SIZE"));
      }

      @Override
      public LambdaLogger getLogger() {
        return LambdaRuntime.getLogger();
      }
    };

    if (parameters.length == 2) {
      parameters[1] = context;
    } else if (parameters.length == 1 && firstParameterType.equals(Context.class)) {
      parameters[0] = context;
    }
  }

  private static JsonNode node(String value) throws IOException {
    return (JsonNode) mjf.createParser(value).readValueAsTree();
  }

  private static void sendError(Throwable e, URI errorUrl) throws IOException, InterruptedException {
    StringWriter sw = new StringWriter();
    JsonGenerator g = mjf.createGenerator(sw);
    g.writeStartObject();
    g.writeStringField("errorMessage", e.getMessage());
    g.writeStringField("errorType", e.getClass().getSimpleName());
    g.writeEndObject();
    g.flush();

    HttpRequest errorRequest = HttpRequest.newBuilder(errorUrl)
            .POST(HttpRequest.BodyPublishers.ofString(sw.toString())).build();
    hc.send(errorRequest, BodyHandlers.discarding());
  }
}
