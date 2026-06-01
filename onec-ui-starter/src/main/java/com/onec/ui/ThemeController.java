package com.onec.ui;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ui")
public class ThemeController {

    private final UiProperties properties;

    public ThemeController(UiProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/theme")
    public Map<String, String> getTheme() {
        return properties.getTheme();
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        return Map.of(
                "readOnly", properties.isReadOnly(),
                "basePath", properties.getPath()
        );
    }
}
