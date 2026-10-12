package com.example.urlshortener.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!migrate")
@EnableConfigurationProperties(WebSettings.class)
public class WebConfiguration {}
