package com.sampullara.awslambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingJsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

public class LambdaProxyTest {
  private MappingJsonFactory mjf = new MappingJsonFactory();

  public JsonNode testHttp(JsonNode request) {
    // All lambda proxy responses are formatted like this
    ObjectMapper c = mjf.getCodec();
    ObjectNode root = c.createObjectNode();
    root.put("statusCode", 200);
    root.set("headers", c.createObjectNode().put("Content-Type", "application/json"));
    root.put("body", request.toString());
    return root;
  }
}

/*

var response = {
        statusCode: responseCode,
        headers: {
            "x-custom-header" : "my custom header value"
        },
        body: JSON.stringify(responseBody)
    };
 */