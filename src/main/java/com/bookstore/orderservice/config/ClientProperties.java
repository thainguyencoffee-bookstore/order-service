package com.bookstore.orderservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.validation.constraints.NotNull;
import java.net.URI;

@ConfigurationProperties(prefix = "bookstore")
public class ClientProperties {

    @NotNull
    URI catalogServiceUri;

}