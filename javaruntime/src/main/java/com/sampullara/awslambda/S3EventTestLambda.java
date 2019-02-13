package com.sampullara.awslambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;


public class S3EventTestLambda implements RequestHandler<S3Event, String> {
  @Override
  public String handleRequest(S3Event s3Event, Context context) {
    return s3Event.getRecords().get(0).getEventName();
  }
}
