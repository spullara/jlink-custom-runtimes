package com.sampullara.dropwizard;

import io.dropwizard.Application;
import io.dropwizard.jersey.DropwizardResourceConfig;
import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.process.internal.RequestScoped;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class HelloWorldApplication extends Application<HelloWorldConfiguration> {
  public static void main(String[] args) throws Exception {
    new HelloWorldApplication().run(args);
  }

  @Override
  public String getName() {
    return "hello-world";
  }

  @Override
  public void initialize(Bootstrap<HelloWorldConfiguration> bootstrap) {
    // nothing to do yet
  }

  @Override
  public void run(HelloWorldConfiguration configuration,
                  Environment environment) {
    final HelloWorldResource resource = new HelloWorldResource(
            configuration.getTemplate(),
            configuration.getDefaultName()
    );
    final TemplateHealthCheck healthCheck =
            new TemplateHealthCheck(configuration.getTemplate());
    environment.healthChecks().register("template", healthCheck);
    JerseyEnvironment jersey = environment.jersey();
    DropwizardResourceConfig resourceConfig = jersey.getResourceConfig();
    resourceConfig
            .packages("com.amazonaws.serverless.proxy.test.jersey")
            .register(new AbstractBinder() {
              @Override
              protected void configure() {
                bindFactory(AwsProxyServletContextFactory.class)
                        .to(ServletContext.class).in(RequestScoped.class);
                bindFactory(AwsProxyServletRequestFactory.class)
                        .to(HttpServletRequest.class).in(RequestScoped.class);
                bindFactory(AwsProxyServletResponseFactory.class)
                        .to(HttpServletResponse.class).in(RequestScoped.class);
              }
            });
    jersey.register(resource);
  }
}