package com.secret_message.secret_message_app.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SecretMessageAppController {

    @GetMapping("/status")
    public String returnUpAndRunning() {
        return "UP AND RUNNING! \uD83E\uDD18 \uD83E\uDD18 \uD83E\uDD18 \n";
    }
}
