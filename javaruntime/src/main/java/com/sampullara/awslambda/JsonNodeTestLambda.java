package com.sampullara.awslambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;

public class JsonNodeTestLambda {
  public JsonNode testNode(JsonNode node, Context context) {
    context.getLogger().log(node.toString());
    return node;
  }
}
