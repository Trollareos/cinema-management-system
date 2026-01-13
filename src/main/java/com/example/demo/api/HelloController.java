package com.example.demo.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    @GetMapping("/")
    public String home() {
        return "Server is running";
    }

    @GetMapping("/hello")
    public String hello() {
        return "OK";
    }
}
