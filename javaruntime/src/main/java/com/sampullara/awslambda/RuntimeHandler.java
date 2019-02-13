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
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingJsonFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Custom runtime handler for executing Java 11 applications
 */
public class RuntimeHandler {
  private final static MappingJsonFactory mjf = new MappingJsonFactory();

  public static void main(String[] args) throws IOException {
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
        URL nextUrl = new URL(next);
        HttpURLConnection nextUrlc = (HttpURLConnection) nextUrl.openConnection();
        nextUrlc.setDoInput(true);
        String requestId = nextUrlc.getHeaderField("Lambda-Runtime-Aws-Request-Id");
        String deadlineMs = nextUrlc.getHeaderField("Lambda-Runtime-Deadline-Ms");
        String functionArn = nextUrlc.getHeaderField("Lambda-Runtime-Invoked-Function-Arn");
        String traceId = nextUrlc.getHeaderField("Lambda-Runtime-Trace-Id");
        System.setProperty("_X_AMZN_TRACE_ID", traceId);
        try {
          String value = new String(nextUrlc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
          boolean isJson = nextUrlc.getContentType().contains("json");
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
                parameters[0] = isJson ? ((float)node(value).asDouble()) : Float.parseFloat(value);
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

          // Construct response
          URL responseUrl = new URL(invocationBase + requestId + "/response");
          HttpURLConnection responseUrlc = (HttpURLConnection) responseUrl.openConnection();
          responseUrlc.setDoOutput(true);

          // Not sure what this should be yet
          responseUrlc.addRequestProperty("Content-Type", "application/json");

          // Write the output and complete the request
          OutputStream os = responseUrlc.getOutputStream();
          JsonGenerator g = mjf.createGenerator(os, JsonEncoding.UTF8);
          g.writeObject(invoked);
          g.flush();
          os.close();
          responseUrlc.getResponseCode();
        } catch (Throwable e) {
          e.printStackTrace();
          // Normal error from execution
          sendError(e, new URL(invocationBase + requestId + "/error"));
        }
      }
    } catch (Throwable e) {
      e.printStackTrace();
      // Initialization error
      sendError(e, new URL(base + "init/error"));
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
    return (JsonNode)mjf.createParser(value).readValueAsTree();
  }

  private static void sendError(Throwable e, URL errorUrl) throws IOException {
    HttpURLConnection errorUrlc = (HttpURLConnection) errorUrl.openConnection();
    errorUrlc.setDoOutput(true);
    OutputStream os = errorUrlc.getOutputStream();
    JsonGenerator g = mjf.createGenerator(os, JsonEncoding.UTF8);
    g.writeStartObject();
    g.writeStringField("errorMessage", e.getMessage());
    g.writeStringField("errorType", e.getClass().getSimpleName());
    g.writeEndObject();
    g.flush();
    os.close();
    errorUrlc.getResponseCode();
  }
}
