package com.onec.ui;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves index.html for /ui and /ui/ without changing the URL,
 * so React Router sees "/" as the path (relative to basename).
 */
@RestController
class SpaIndexController {

    private static final Resource INDEX = new ClassPathResource("static/ui/index.html");

    @GetMapping(value = {"/ui", "/ui/"}, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public Resource index() {
        return INDEX;
    }
}
