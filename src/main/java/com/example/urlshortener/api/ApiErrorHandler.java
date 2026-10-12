package com.example.urlshortener.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Profile("!migrate")
public class ApiErrorHandler {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> application(
      ApiException failure, HttpServletRequest request, HttpServletResponse response) {
    return error(failure, request, response);
  }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  ResponseEntity<?> dependency(
      Exception failure, HttpServletRequest request, HttpServletResponse response) {
    return error(ApiException.unavailable(), request, response);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<?> missing(HttpServletRequest request, HttpServletResponse response) {
    return error(ApiException.notFound(), request, response);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<?> method(HttpServletRequest request, HttpServletResponse response) {
    response.setHeader(
        "Allow",
        request.getRequestURI().startsWith("/r/")
            ? "GET, HEAD"
            : request.getRequestURI().equals("/api/v1/links") ? "POST" : "GET, HEAD, DELETE");
    return error(
        new ApiException(405, "METHOD_NOT_ALLOWED", "The method is not supported."),
        request,
        response);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(
      Exception failure, HttpServletRequest request, HttpServletResponse response) {
    return error(
        new ApiException(500, "INTERNAL_ERROR", "An internal error occurred."), request, response);
  }

  private ResponseEntity<?> error(
      ApiException failure, HttpServletRequest request, HttpServletResponse response) {
    if (failure.status() == 405 && request.getRequestURI().startsWith("/r/")) {
      response.setHeader("Allow", "GET, HEAD");
    }
    return ResponseEntity.status(failure.status())
        .body(request.getMethod().equals("HEAD") ? null : ErrorResponses.body(response, failure));
  }
}
