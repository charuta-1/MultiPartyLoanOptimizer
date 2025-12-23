package com.smartsplitpro.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;

@ControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public String handleAnyException(HttpServletRequest req, Exception ex, Model model) {
        // Log full stacktrace for debugging
        log.error("Unhandled exception for request {}", req.getRequestURI(), ex);

        // Add a friendly message (don't expose internals in production)
        model.addAttribute("message", ex.getMessage() == null ? "Unexpected server error" : ex.getMessage());
        model.addAttribute("path", req.getRequestURI());
        return "error";
    }
}
